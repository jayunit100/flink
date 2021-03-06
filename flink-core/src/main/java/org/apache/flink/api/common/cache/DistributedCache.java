/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.flink.api.common.cache;


import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;

/**
 * DistributedCache provides static methods to write the registered cache files into job configuration or decode
 * them from job configuration. It also provides user access to the file locally.
 */
public class DistributedCache {
	
	public static class DistributedCacheEntry {
		
		public String filePath;
		public Boolean isExecutable;
		
		public DistributedCacheEntry(String filePath, Boolean isExecutable){
			this.filePath=filePath;
			this.isExecutable=isExecutable;
		}
	}
	
	// ------------------------------------------------------------------------

	private final Map<String, Future<Path>> cacheCopyTasks;

	public DistributedCache(Map<String, Future<Path>> cacheCopyTasks) {
		this.cacheCopyTasks = cacheCopyTasks;
	}

	// ------------------------------------------------------------------------

	public File getFile(String name) {
		if (name == null) {
			throw new NullPointerException("name must not be null");
		}
		
		Future<Path> future = cacheCopyTasks.get(name);
		if (future == null) {
			throw new IllegalArgumentException("File with name '" + name + "' is not available." +
					" Did you forget to register the file?");
		}
		
		try {
			Path tmp = future.get();
			return new File(tmp.toString());
		}
		catch (ExecutionException e) {
			throw new RuntimeException("An error occurred while copying the file.", e.getCause());
		}
		catch (Exception e) {
			throw new RuntimeException("Error while getting the file registered under '" + name +
					"' from the distributed cache", e);
		}
	}
	
	// ------------------------------------------------------------------------
	//  Utilities to read/write cache files from/to the configuration
	// ------------------------------------------------------------------------
	
	public static void writeFileInfoToConfig(String name, DistributedCacheEntry e, Configuration conf) {
		int num = conf.getInteger(CACHE_FILE_NUM,0) + 1;
		conf.setInteger(CACHE_FILE_NUM, num);
		conf.setString(CACHE_FILE_NAME + num, name);
		conf.setString(CACHE_FILE_PATH + num, e.filePath);
		conf.setBoolean(CACHE_FILE_EXE + num, e.isExecutable || new File(e.filePath).canExecute());
	}

	public static Set<Entry<String, DistributedCacheEntry>> readFileInfoFromConfig(Configuration conf) {
		int num = conf.getInteger(CACHE_FILE_NUM, 0);
		if (num == 0) {
			return Collections.emptySet();
		}

		Map<String, DistributedCacheEntry> cacheFiles = new HashMap<String, DistributedCacheEntry>();
		for (int i = 1; i <= num; i++) {
			String name = conf.getString(CACHE_FILE_NAME + i, null);
			String filePath = conf.getString(CACHE_FILE_PATH + i, null);
			Boolean isExecutable = conf.getBoolean(CACHE_FILE_EXE + i, false);
			cacheFiles.put(name, new DistributedCacheEntry(filePath, isExecutable));
		}
		return cacheFiles.entrySet();
	}

	private static final String CACHE_FILE_NUM = "DISTRIBUTED_CACHE_FILE_NUM";

	private static final String CACHE_FILE_NAME = "DISTRIBUTED_CACHE_FILE_NAME_";

	private static final String CACHE_FILE_PATH = "DISTRIBUTED_CACHE_FILE_PATH_";

	private static final String CACHE_FILE_EXE = "DISTRIBUTED_CACHE_FILE_EXE_";
}
