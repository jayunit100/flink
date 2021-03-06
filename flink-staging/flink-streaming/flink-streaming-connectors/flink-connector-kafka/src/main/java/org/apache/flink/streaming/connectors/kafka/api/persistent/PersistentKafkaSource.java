/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.kafka.api.persistent;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import kafka.common.TopicAndPartition;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;
import kafka.utils.ZKGroupTopicDirs;
import kafka.utils.ZkUtils;

import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.exception.ZkMarshallingError;
import org.I0Itec.zkclient.serialize.ZkSerializer;
import org.apache.flink.api.common.state.OperatorState;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.StateHandle;
import org.apache.flink.streaming.api.checkpoint.CheckpointCommitter;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.streaming.util.serialization.DeserializationSchema;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Option;
import scala.collection.JavaConversions;
import scala.collection.Seq;

import com.google.common.base.Preconditions;

/**
 * Source for reading from Kafka using Flink Streaming Fault Tolerance.
 * This source is updating the committed offset in Zookeeper based on the internal checkpointing of Flink.
 *
 * Note that the autocommit feature of Kafka needs to be disabled for using this source.
 */
public class PersistentKafkaSource<OUT> extends RichParallelSourceFunction<OUT> implements
		ResultTypeQueryable<OUT>,
		CheckpointCommitter {

	private static final long serialVersionUID = 287845877188312621L;
	
	private static final Logger LOG = LoggerFactory.getLogger(PersistentKafkaSource.class);

	
	private final String topicName;
	private final DeserializationSchema<OUT> deserializationSchema;
	
	private transient ConsumerConfig consumerConfig;
	private transient ConsumerIterator<byte[], byte[]> iteratorToRead;
	private transient ConsumerConnector consumer;
	
	private transient ZkClient zkClient;
	private transient OperatorState<long[]> lastOffsets;
	private transient long[] commitedOffsets; // maintain committed offsets, to avoid committing the same over and over again.
	
	private volatile boolean running;
	
	/**
	 *
	 * For the @param consumerConfig, specify at least the "groupid" and "zookeeper.connect" string.
	 * The config will be passed into the Kafka High Level Consumer.
	 * For a full list of possible values, check this out: https://kafka.apache.org/documentation.html#consumerconfigs
	 */
	public PersistentKafkaSource(String topicName, DeserializationSchema<OUT> deserializationSchema, ConsumerConfig consumerConfig) {
		Preconditions.checkNotNull(topicName);
		Preconditions.checkNotNull(deserializationSchema);
		Preconditions.checkNotNull(consumerConfig);

		this.topicName = topicName;
		this.deserializationSchema = deserializationSchema;
		this.consumerConfig = consumerConfig;
		if (consumerConfig.autoCommitEnable()) {
			throw new IllegalArgumentException("'auto.commit.enable' is set to 'true'. " +
					"This source can only be used with auto commit disabled because the " +
					"source is committing to zookeeper by itself (not using the KafkaConsumer).");
		}
		if (!consumerConfig.offsetsStorage().equals("zookeeper")) {
			// we can currently only commit to ZK.
			throw new IllegalArgumentException("The 'offsets.storage' has to be set to 'zookeeper' for this Source to work reliably");
		}
	}

	// ---------------------- ParallelSourceFunction Lifecycle -----------------


	@Override
	public void open(Configuration parameters) throws Exception {
		super.open(parameters);
		ConsumerConnector consumer = Consumer.createJavaConsumerConnector(this.consumerConfig);
		// we request only one stream per consumer instance. Kafka will make sure that each consumer group
		// will see each message only once.
		Map<String,Integer> topicCountMap = Collections.singletonMap(topicName, 1);
		Map<String, List<KafkaStream<byte[], byte[]>>> streams = consumer.createMessageStreams(topicCountMap);
		if(streams.size() != 1) {
			throw new RuntimeException("Expected only one message stream but got "+streams.size());
		}
		List<KafkaStream<byte[], byte[]>> kafkaStreams = streams.get(topicName);
		if(kafkaStreams == null) {
			throw new RuntimeException("Requested stream not available. Available streams: "+streams.toString());
		}
		if(kafkaStreams.size() != 1) {
			throw new RuntimeException("Requested 1 stream from Kafka, bot got "+kafkaStreams.size()+" streams");
		}
		LOG.info("Opening Consumer instance for topic '{}' on group '{}'", topicName, consumerConfig.groupId());
		this.iteratorToRead = kafkaStreams.get(0).iterator();
		this.consumer = consumer;

		zkClient = new ZkClient(consumerConfig.zkConnect(),
				consumerConfig.zkSessionTimeoutMs(),
				consumerConfig.zkConnectionTimeoutMs(),
				new KafkaZKStringSerializer());

		// most likely the number of offsets we're going to store here will be lower than the number of partitions.
		int numPartitions = getNumberOfPartitions();
		LOG.debug("The topic {} has {} partitions", topicName, numPartitions);
		this.lastOffsets = getRuntimeContext().getOperatorState("offset", new long[numPartitions], false);
		this.commitedOffsets = new long[numPartitions];
		// check if there are offsets to restore
		if (!Arrays.equals(lastOffsets.value(), new long[numPartitions])) {
			if (lastOffsets.value().length != numPartitions) {
				throw new IllegalStateException("There are "+lastOffsets.value().length+" offsets to restore for topic "+topicName+" but " +
						"there are only "+numPartitions+" in the topic");
			}

			LOG.info("Setting restored offsets {} in ZooKeeper", Arrays.toString(lastOffsets.value()));
			setOffsetsInZooKeeper(lastOffsets.value());
		} else {
			// initialize empty offsets
			Arrays.fill(this.lastOffsets.value(), -1);
		}
		Arrays.fill(this.commitedOffsets, 0); // just to make it clear
		
		running = true;
	}

	@Override
	public void run(SourceContext<OUT> ctx) throws Exception {
		if (iteratorToRead == null) {
			throw new IllegalStateException("Kafka iterator not initialized properly.");
		}

		final Object checkpointLock = ctx.getCheckpointLock();
		
		while (running && iteratorToRead.hasNext()) {
			MessageAndMetadata<byte[], byte[]> message = iteratorToRead.next();
			if(lastOffsets.value()[message.partition()] >= message.offset()) {
				LOG.info("Skipping message with offset {} from partition {}", message.offset(), message.partition());
				continue;
			}
			OUT next = deserializationSchema.deserialize(message.message());

			if (deserializationSchema.isEndOfStream(next)) {
				LOG.info("DeserializationSchema signaled end of stream for this source");
				break;
			}

			// make the state update and the element emission atomic
			synchronized (checkpointLock) {
				lastOffsets.value()[message.partition()] = message.offset();
				ctx.collect(next);
			}

			if (LOG.isTraceEnabled()) {
				LOG.trace("Processed record with offset {} from partition {}", message.offset(), message.partition());
			}
		}
	}

	@Override
	public void cancel() {
		running = false;
	}

	@Override
	public void close() {
		LOG.info("Closing Kafka consumer");
		this.consumer.shutdown();
		zkClient.close();
	}

	
	/**
	 * Notification on completed checkpoints
	 * @param checkpointId The ID of the checkpoint that has been completed.
	 * @throws Exception 
	 */
	@Override
	public void commitCheckpoint(long checkpointId, String stateName, StateHandle<Serializable> state) throws Exception {
		LOG.info("Commit checkpoint {}", checkpointId);

		long[] checkpointOffsets;

		checkpointOffsets = (long[]) state.getState();

		if (LOG.isInfoEnabled()) {
			LOG.info("Committing offsets {} to ZooKeeper", Arrays.toString(checkpointOffsets));
		}

		setOffsetsInZooKeeper(checkpointOffsets);
	}

	private void setOffsetsInZooKeeper(long[] offsets) {
		for (int partition = 0; partition < offsets.length; partition++) {
			long offset = offsets[partition];
			if(offset != -1) {
				setOffset(partition, offset);
			}
		}
	}

	// --------------------- Zookeeper / Offset handling -----------------------------

	private int getNumberOfPartitions() {
		scala.collection.immutable.List<String> scalaSeq = JavaConversions.asScalaBuffer(Collections.singletonList(topicName)).toList();
		scala.collection.mutable.Map<String, Seq<Object>> list =  ZkUtils.getPartitionsForTopics(zkClient, scalaSeq);
		Option<Seq<Object>> topicOption = list.get(topicName);
		if(topicOption.isEmpty()) {
			throw new IllegalArgumentException("Unable to get number of partitions for topic "+topicName+" from "+list.toString());
		}
		Seq<Object> topic = topicOption.get();
		return topic.size();
	}

	protected void setOffset(int partition, long offset) {
		if(commitedOffsets[partition] < offset) {
			setOffset(zkClient, consumerConfig.groupId(), topicName, partition, offset);
			commitedOffsets[partition] = offset;
		} else {
			LOG.debug("Ignoring offset {} for partition {} because it is already committed", offset, partition);
		}
	}



	// the following two methods are static to allow access from the outside as well (Testcases)

	/**
	 * This method's code is based on ZookeeperConsumerConnector.commitOffsetToZooKeeper()
	 */
	public static void setOffset(ZkClient zkClient, String groupId, String topic, int partition, long offset) {
		LOG.info("Setting offset for partition {} of topic {} in group {} to offset {}", partition, topic, groupId, offset);
		TopicAndPartition tap = new TopicAndPartition(topic, partition);
		ZKGroupTopicDirs topicDirs = new ZKGroupTopicDirs(groupId, tap.topic());
		ZkUtils.updatePersistentPath(zkClient, topicDirs.consumerOffsetDir() + "/" + tap.partition(), Long.toString(offset));
	}

	public static long getOffset(ZkClient zkClient, String groupId, String topic, int partition) {
		TopicAndPartition tap = new TopicAndPartition(topic, partition);
		ZKGroupTopicDirs topicDirs = new ZKGroupTopicDirs(groupId, tap.topic());
		scala.Tuple2<String, Stat> data = ZkUtils.readData(zkClient, topicDirs.consumerOffsetDir() + "/" + tap.partition());
		return Long.valueOf(data._1());
	}


	// ---------------------- (Java)Serialization methods for the consumerConfig -----------------

	private void writeObject(ObjectOutputStream out)
			throws IOException, ClassNotFoundException {
		out.defaultWriteObject();
		out.writeObject(consumerConfig.props().props());
	}

	private void readObject(ObjectInputStream in)
			throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		Properties props = (Properties) in.readObject();
		consumerConfig = new ConsumerConfig(props);
	}


	@Override
	public TypeInformation<OUT> getProducedType() {
		return deserializationSchema.getProducedType();
	}


	// ---------------------- Zookeeper Serializer copied from Kafka (because it has private access there)  -----------------

	public static class KafkaZKStringSerializer implements ZkSerializer {

		@Override
		public byte[] serialize(Object data) throws ZkMarshallingError {
			try {
				return ((String) data).getBytes("UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public Object deserialize(byte[] bytes) throws ZkMarshallingError {
			if (bytes == null) {
				return null;
			} else {
				try {
					return new String(bytes, "UTF-8");
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}
}
