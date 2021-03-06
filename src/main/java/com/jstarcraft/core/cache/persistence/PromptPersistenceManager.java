package com.jstarcraft.core.cache.persistence;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jstarcraft.core.cache.CacheInformation;
import com.jstarcraft.core.cache.CacheObject;
import com.jstarcraft.core.cache.CacheState;
import com.jstarcraft.core.cache.persistence.PersistenceStrategy.PersistenceOperation;
import com.jstarcraft.core.cache.proxy.ProxyObject;
import com.jstarcraft.core.orm.OrmAccessor;
import com.jstarcraft.core.utility.StringUtility;

/**
 * 立即持久策略
 * 
 * @author Birdy
 *
 */
public class PromptPersistenceManager<K extends Comparable, T extends CacheObject<K>> implements PersistenceManager<K, T> {

	private static final Logger LOGGER = LoggerFactory.getLogger(PromptPersistenceManager.class);

	/** 名称 */
	private String name;
	/** 类型 */
	private Class cacheClass;

	/** 读写锁 */
	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	/** ORM访问器 */
	private OrmAccessor accessor;
	/** 缓存类型信息 */
	private CacheInformation information;
	/** 状态 */
	private AtomicReference<CacheState> state;
	/** 监听器 */
	private PersistenceMonitor monitor;
	/** 创建统计 */
	private final AtomicLong createdCount = new AtomicLong();
	/** 更新统计 */
	private final AtomicLong updatedCount = new AtomicLong();
	/** 删除统计 */
	private final AtomicLong deletedCount = new AtomicLong();
	/** 异常统计 */
	private final AtomicLong exceptionCount = new AtomicLong();

	PromptPersistenceManager(String name, Class cacheClass, OrmAccessor accessor, CacheInformation information, AtomicReference<CacheState> state) {
		this.name = name;
		this.cacheClass = cacheClass;
		this.accessor = accessor;
		this.information = information;
		this.state = state;
	}

	@Override
	public T getInstance(K cacheId) {
		Lock readLock = lock.readLock();
		try {
			readLock.lock();
			T value = (T) accessor.get(cacheClass, cacheId);
			return value;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public Map<K, Object> getIdentities(String indexName, Comparable indexValue) {
		Lock readLock = lock.readLock();
		try {
			readLock.lock();
			Map<K, Object> values = accessor.queryIdentities(cacheClass, indexName, indexValue);
			return values;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public List<T> getInstances(String indexName, Comparable indexValue) {
		Lock readLock = lock.readLock();
		try {
			readLock.lock();
			List<T> values = accessor.queryInstances(cacheClass, indexName, indexValue);
			return values;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public PersistenceElement createInstance(CacheObject<?> cacheObject) {
		if (cacheObject instanceof ProxyObject) {
			cacheObject = ((ProxyObject) cacheObject).getInstance();
		}
		PersistenceElement element = new PersistenceElement(PersistenceOperation.CREATE, cacheObject.getId(), cacheObject);
		Exception exception = null;
		synchronized (cacheObject) {
			Lock writeLock = lock.writeLock();
			try {
				writeLock.lock();
				accessor.create(cacheClass, element.getCacheObject());
				createdCount.incrementAndGet();
			} catch (Exception throwable) {
				String message = StringUtility.format("立即策略[{}]处理元素[{}]时异常", new Object[] { name, element });
				LOGGER.error(message, throwable);
				exception = throwable;
				exceptionCount.incrementAndGet();
			} finally {
				writeLock.unlock();
			}
		}
		if (monitor != null) {
			monitor.notifyOperate(element.getOperation(), element.getCacheId(), element.getCacheObject(), exception);
		}
		return element;
	}

	@Override
	public PersistenceElement deleteInstance(Comparable cacheId) {
		PersistenceElement element = new PersistenceElement(PersistenceOperation.DELETE, cacheId, null);
		Exception exception = null;
		Lock writeLock = lock.writeLock();
		try {
			writeLock.lock();
			accessor.delete(cacheClass, element.getCacheId());
			deletedCount.incrementAndGet();
		} catch (Exception throwable) {
			String message = StringUtility.format("立即策略[{}]处理元素[{}]时异常", new Object[] { name, element });
			LOGGER.error(message, throwable);
			exception = throwable;
			exceptionCount.incrementAndGet();
		} finally {
			writeLock.unlock();
		}
		if (monitor != null) {
			monitor.notifyOperate(element.getOperation(), element.getCacheId(), element.getCacheObject(), exception);
		}
		return element;
	}

	@Override
	public PersistenceElement updateInstance(CacheObject<?> cacheObject) {
		if (cacheObject instanceof ProxyObject) {
			cacheObject = ((ProxyObject) cacheObject).getInstance();
		}
		PersistenceElement element = new PersistenceElement(PersistenceOperation.UPDATE, cacheObject.getId(), cacheObject);
		Exception exception = null;
		synchronized (cacheObject) {
			Lock writeLock = lock.writeLock();
			try {
				writeLock.lock();
				accessor.update(cacheClass, element.getCacheObject());
				updatedCount.incrementAndGet();
			} catch (Exception throwable) {
				String message = StringUtility.format("立即策略[{}]处理元素[{}]时异常", new Object[] { name, element });
				LOGGER.error(message, throwable);
				exception = throwable;
				exceptionCount.incrementAndGet();
			} finally {
				writeLock.unlock();
			}
		}
		if (monitor != null) {
			monitor.notifyOperate(element.getOperation(), element.getCacheId(), element.getCacheObject(), exception);
		}
		return element;
	}

	@Override
	public void setMonitor(PersistenceMonitor monitor) {
		this.monitor = monitor;
	}

	@Override
	public PersistenceMonitor getMonitor() {
		return monitor;
	}

	@Override
	public int getWaitSize() {
		return 0;
	}

	@Override
	public long getCreatedCount() {
		return createdCount.get();
	}

	@Override
	public long getUpdatedCount() {
		return updatedCount.get();
	}

	@Override
	public long getDeletedCount() {
		return deletedCount.get();
	}

	@Override
	public long getExceptionCount() {
		return exceptionCount.get();
	}

}
