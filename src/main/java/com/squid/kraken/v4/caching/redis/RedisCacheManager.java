/*******************************************************************************
 * Copyright © Squid Solutions, 2016
 *
 * This file is part of Open Bouquet software.
 *  
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * There is a special FOSS exception to the terms and conditions of the 
 * licenses as they are applied to this program. See LICENSE.txt in
 * the directory of this program distribution.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * Squid Solutions also offers commercial licenses with additional warranties,
 * professional functionalities or services. If you purchase a commercial
 * license, then it supersedes and replaces any other agreement between
 * you and Squid Solutions (above licenses and LICENSE.txt included).
 * See http://www.squidsolutions.com/EnterpriseBouquet/
 *******************************************************************************/
package com.squid.kraken.v4.caching.redis;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.squid.kraken.v4.caching.redis.datastruct.RawMatrix;
import com.squid.kraken.v4.caching.redis.datastruct.RedisCacheReference;
import com.squid.kraken.v4.caching.redis.datastruct.RedisCacheValue;
import com.squid.kraken.v4.caching.redis.datastruct.RedisCacheValuesList;
import com.squid.kraken.v4.caching.redis.generationalkeysserver.GenerationalKeysServerFactory;
import com.squid.kraken.v4.caching.redis.generationalkeysserver.IGenerationalKeysServer;
import com.squid.kraken.v4.caching.redis.generationalkeysserver.RedisKey;
import com.squid.kraken.v4.caching.redis.queriesserver.IQueriesServer;
import com.squid.kraken.v4.caching.redis.queriesserver.QueriesServerFactory;

public class RedisCacheManager implements IRedisCacheManager {

	static final Logger logger = LoggerFactory.getLogger(RedisCacheManager.class);

	private static IRedisCacheManager INSTANCE;

	private static boolean isMock = false;

	private IRedisCacheProxy redis;
	private RedisCacheConfig conf;
	private IQueriesServer queriesServ;
	private IGenerationalKeysServer genkeysServ;

	// constructors

	public RedisCacheManager() {
	}

	public static void setMock() {
		isMock = true;
	}

	public static IRedisCacheManager getInstance() {
		if (INSTANCE == null) {
			if (isMock) {
				INSTANCE = new RedisCacheManagerMock();
			} else {
				INSTANCE = new RedisCacheManager();
			}
		}
		return INSTANCE;
	}

	public void setConfig(RedisCacheConfig confCache) {
		this.conf = confCache;
	}

	public void startCacheManager() {
		logger.info("starting cache manager");

		this.genkeysServ = GenerationalKeysServerFactory.INSTANCE.getNewGenerationalKeysServer(conf, false);
		this.queriesServ = QueriesServerFactory.INSTANCE.getNewQueriesServer(conf, false);

		this.genkeysServ.start();
		this.queriesServ.start();

		this.redis = RedisCacheProxy.getInstance(conf.getRedisID());

	}

	public RawMatrix getData(String SQLQuery, List<String> dependencies, String jobId, String RSjdbcURL,
			String username, String pwd, int TTLinSec, long limit) throws InterruptedException {
		// generate the key by adding projectID and SQL
		String k = buildCacheKey(SQLQuery, dependencies);

		RawMatrix res = getRawMatrix(k);
		if (res != null) {
			logger.debug("cache hit for key = " + k);
			res.setFromCache(true);
		} else {
			int queryNum = this.fetch(k, SQLQuery, jobId, RSjdbcURL, username, pwd, TTLinSec, limit);
			if (queryNum == -1) {
				logger.info(
						"failed to fetch result for job :" + jobId + "\nSQLQuery:\n " + SQLQuery + "\nfetch failed");
				return null;
			}
			res = getRawMatrix(k);
			res.setFromCache(false);
		}

		return res;
	}

	public RawMatrix getDataLazy(String SQLQuery, List<String> dependencies, String RSjdbcURL, String username,
			String pwd, int TTLinSec) {
		String k = buildCacheKey(SQLQuery, dependencies);
		RawMatrix res = getRawMatrix(k);
		if (res != null) {
			logger.debug("cache hit for key = " + k);
			res.setFromCache(true);
		} else {
			res = null;
		}

		return res;
	}

	public RedisCacheValue getRedisCacheValueLazy(String SQLQuery, List<String> dependencies, String RSjdbcURL,
			String username, String pwd, int TTLinSec) {
		String k = buildCacheKey(SQLQuery, dependencies);
		RedisCacheValue val = this.redis.getRawOrList(k);

		if (val!=null) {
			val.setFromCache(true);
			if (val instanceof RedisCacheValuesList) {
				return validateCacheList((RedisCacheValuesList) val);
			} else {
				return val;
			}
		} else {
			return null;
		}
	}

	public RedisCacheValue getRedisCacheValue(String SQLQuery, List<String> dependencies, String jobId,
			String RSjdbcURL, String username, String pwd, int TTLinSec, long limit) throws InterruptedException {
		String k = buildCacheKey(SQLQuery, dependencies);
		RedisCacheValue val = this.redis.getRawOrList(k);
		if (val != null) {
			val.setFromCache(true);
			if(val instanceof RedisCacheValuesList){
				RedisCacheValuesList validated = validateCacheList( (RedisCacheValuesList) val ); 
				if (validated!=null){
					return validated;
				} else {
					logger.info(" The analysis " + jobId + "  did not end properly, recomputing " + SQLQuery);
				}
			} else {
				return val;
			}
		}
		int queryId = this.fetch(k, SQLQuery, jobId, RSjdbcURL, username, pwd, TTLinSec, limit);
		if (queryId == -1) {
			logger.info("failed to fetch result for job :" + jobId + "\nSQLQuery:\n " + SQLQuery + "\nfetch failed");
			return null;
		}
		val = this.redis.getRawOrList(k);
		if (val instanceof RedisCacheValuesList) {
			return validateCacheList((RedisCacheValuesList) val);
		} else {
			return val;
		}
	}

	private RedisCacheValuesList validateCacheList(RedisCacheValuesList list) {
		if (list.isDone()) {
			logger.debug("done");
			return list;
		}
		if (list.isError()) {
			logger.debug("error");
			return null;
		}
		if (list.isOngoing()) {
			boolean isOngoing = this.queriesServ.isQueryOngoing(list.getRedisKey());
			if (isOngoing) {
				logger.debug("really ongoing");
				return list;
			} else {
				// check if the state has changed to DONE
				RedisCacheValue val = this.redis.getRawOrList(list.getRedisKey());
				if (val instanceof RedisCacheValuesList) {
					RedisCacheValuesList newList = (RedisCacheValuesList) val;
					if (newList.isDone()) {
						logger.debug("was ongoing, done now");
						return newList;
					}
					if (newList.isError()) {
						logger.debug("was ongoing, error now");
						return null;
					}
					if (newList.isOngoing()) {
						logger.debug("still ongoing status, although not being computed, setting to ERROR");

						newList.setError();
						this.redis.put(list.getRedisKey(), newList.serialize());
						return null;
					}
				} else {
					return null;
				}
			}
		}
		return null;
	}

	public String addCacheReference(String sqlNoLimit, List<String> dependencies, String referencedKey) {
		try {
			String k = buildCacheKey(sqlNoLimit, dependencies);
			logger.debug("Add reference key : " + k + "    " + referencedKey);
			RedisCacheReference ref = new RedisCacheReference(referencedKey);
			boolean ok = this.redis.put(k, ref.serialize());
			if (ok) {
				return k;
			} else {
				return null;
			}
		} catch (IOException e) {
			return null;
		}
	}

	private String buildCacheKey(String SQLQuery, List<String> dependencies) {
		String key = "";
		if (dependencies.size() > 0) {
			key += dependencies.get(0);
		}
		key += "-" + DigestUtils.sha256Hex(SQLQuery);
		//
		RedisKey rk = getKey(key, dependencies);
		return rk.getStringKey();
	}

	public void clear() {
		logger.info("Clearing SQL cache");
		this.redis.clear();
	}

	public void refresh(String... dependencies) {
		this.genkeysServ.refresh(Arrays.asList(dependencies));
	}

	public void refresh(List<String> dependencies) {
		this.genkeysServ.refresh(dependencies);
	}

	public void refresh(String key) {
		this.genkeysServ.refresh(Collections.singletonList(key));
	}

	public RedisKey getKey(String key) {
		return this.genkeysServ.getKey(key, null);
	}

	public RedisKey getKey(String key, Collection<String> dependencies) {
		return this.genkeysServ.getKey(key, dependencies);
	}

	public RedisKey getKey(String key, String... dependencies) {
		return this.genkeysServ.getKey(key, Arrays.asList(dependencies));
	}

	/**
	 * get a possibly update RedisKey for this key
	 * 
	 * @param key
	 * @return
	 */
	public RedisKey getKey(RedisKey key) {
		return this.genkeysServ.getKey(key.getName(), key.getDepGen().keySet());
	}

	/**
	 * check if the key is still valid
	 * 
	 * @param key
	 * @return
	 */
	public boolean isValid(RedisKey key) {
		RedisKey check = getKey(key);
		return check.getVersion() == key.getVersion() && check.getUniqueID() == key.getUniqueID();
	}

	public boolean inCache(RedisKey key) {
		return this.redis.inCache(key);
	}

	public boolean inCache(String key) {
		return this.redis.inCache(key);
	}

	private int fetch(String k, String SQLQuery, String jobId, String RSjdbcURL, String username, String pwd, int ttl,
			long limit) throws InterruptedException {
		return this.queriesServ.fetch(k, SQLQuery, jobId, RSjdbcURL, username, pwd, ttl, limit);
	}

	public RawMatrix getRawMatrix(String k) {
		RawMatrix r = this.redis.getRawMatrix(k);
		return r;
	}

}
