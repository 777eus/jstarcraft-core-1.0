package com.jstarcraft.core.cache.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jstarcraft.core.cache.CacheInformation;
import com.jstarcraft.core.cache.CacheObject;
import com.jstarcraft.core.cache.CacheState;
import com.jstarcraft.core.cache.exception.CacheException;
import com.jstarcraft.core.cache.exception.CacheOperationException;
import com.jstarcraft.core.cache.persistence.PersistenceStrategy.PersistenceOperation;
import com.jstarcraft.core.cache.proxy.ProxyObject;
import com.jstarcraft.core.orm.OrmAccessor;
import com.jstarcraft.core.utility.InstantUtility;
import com.jstarcraft.core.utility.StringUtility;

/**
 * 定时持久策略
 * 
 * @author Birdy
 *
 */
public class SchedulePersistenceManager<K extends Comparable, T extends CacheObject<K>> extends Thread implements PersistenceManager<K, T> {

	private static final Logger LOGGER = LoggerFactory.getLogger(SchedulePersistenceManager.class);

	/** 名称 */
	private String name;
	/** 类型 */
	private Class cacheClass;

	/** 此读写锁用于配合elementMap,保证在查询过程中不存在增删改 */
	private ReentrantReadWriteLock waitForLock = new ReentrantReadWriteLock();
	/** 等待的缓存元素实例 */
	private ConcurrentHashMap<Object, PersistenceElement> elements = new ConcurrentHashMap<>();

	/** ORM访问器 */
	private OrmAccessor accessor;
	/** 缓存类型信息 */
	private CacheInformation information;
	/** 状态 */
	private AtomicReference<CacheState> state = new AtomicReference<>(null);
	/** CRON表达式 */
	private String cron;
	/** 持久时间点 */
	private Instant persistTime;
	/** 监听器 */
	private PersistenceMonitor monitor;

	/** 处理大小 */
	private final AtomicInteger waitSize = new AtomicInteger();
	/** 创建统计 */
	private final AtomicLong createdCount = new AtomicLong();
	/** 更新统计 */
	private final AtomicLong updatedCount = new AtomicLong();
	/** 删除统计 */
	private final AtomicLong deletedCount = new AtomicLong();
	/** 异常统计 */
	private final AtomicInteger exceptionCount = new AtomicInteger();

	SchedulePersistenceManager(String name, Class cacheClass, OrmAccessor accessor, CacheInformation information, AtomicReference<CacheState> state, String cron) {
		this.name = name;
		this.cacheClass = cacheClass;
		this.accessor = accessor;
		this.information = information;
		this.state = state;
		this.cron = cron;
		this.persistTime = InstantUtility.getInstantAfter(cron, Instant.now());
	}

	@Override
	public T getInstance(K cacheId) {
		Lock readLock = waitForLock.readLock();
		try {
			readLock.lock();
			PersistenceElement element = elements.get(cacheId);
			if (element != null) {
				if (element.getOperation().equals(PersistenceOperation.DELETE)) {
					return null;
				} else {
					return (T) element.getCacheObject();
				}
			}
			T value = (T) accessor.get(cacheClass, cacheId);
			return value;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public Map<K, Object> getIdentities(String indexName, Comparable indexValue) {
		Lock readLock = waitForLock.readLock();
		try {
			readLock.lock();
			Map<K, Object> values = accessor.queryIdentities(cacheClass, indexName, indexValue);

			for (PersistenceElement element : elements.values()) {
				if (element.getOperation().equals(PersistenceOperation.CREATE)) {
					Object value = information.getIndexValue(element.getCacheObject(), indexName);
					if (indexValue.equals(value)) {
						values.put((K) element.getCacheId(), value);
					}
				}
				if (element.getOperation().equals(PersistenceOperation.UPDATE)) {
					Object value = information.getIndexValue(element.getCacheObject(), indexName);
					if (indexValue.equals(value)) {
						values.put((K) element.getCacheId(), value);
					}
				}
				if (element.getOperation().equals(PersistenceOperation.DELETE)) {
					values.remove(element.getCacheId());
				}
			}
			return values;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public List<T> getInstances(String indexName, Comparable indexValue) {
		Lock readLock = waitForLock.readLock();
		try {
			readLock.lock();
			List<T> values = accessor.queryInstances(cacheClass, indexName, indexValue);

			Map<K, T> instances = new HashMap<>();
			for (T value : values) {
				instances.put(value.getId(), value);
			}

			for (PersistenceElement element : elements.values()) {
				if (element.getOperation().equals(PersistenceOperation.CREATE)) {
					Object value = information.getIndexValue(element.getCacheObject(), indexName);
					if (indexValue.equals(value)) {
						instances.put((K) element.getCacheId(), (T) element.getCacheObject());
					}
				}
				if (element.getOperation().equals(PersistenceOperation.UPDATE)) {
					Object value = information.getIndexValue(element.getCacheObject(), indexName);
					if (indexValue.equals(value)) {
						instances.put((K) element.getCacheId(), (T) element.getCacheObject());
					}
				}
				if (element.getOperation().equals(PersistenceOperation.DELETE)) {
					instances.remove(element.getCacheId());
				}
			}
			return new ArrayList<>(instances.values());
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
		persist(element);
		return element;
	}

	@Override
	public PersistenceElement deleteInstance(Comparable cacheId) {
		PersistenceElement element = new PersistenceElement(PersistenceOperation.DELETE, cacheId, null);
		persist(element);
		return element;
	}

	@Override
	public PersistenceElement updateInstance(CacheObject<?> cacheObject) {
		if (cacheObject instanceof ProxyObject) {
			cacheObject = ((ProxyObject) cacheObject).getInstance();
		}
		PersistenceElement element = new PersistenceElement(PersistenceOperation.UPDATE, cacheObject.getId(), cacheObject);
		persist(element);
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
		synchronized (waitSize) {
			int size = waitSize.get() + elements.size();
			return size;
		}
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

	private ConcurrentHashMap<Object, PersistenceElement> switchElements() {
		synchronized (waitSize) {
			ConcurrentHashMap<Object, PersistenceElement> newElements = new ConcurrentHashMap<>();
			ConcurrentHashMap<Object, PersistenceElement> oldElements = elements;
			elements = newElements;
			waitSize.addAndGet(oldElements.size());
			return oldElements;
		}
	}

	private void persist(PersistenceElement element) {
		if (element == null) {
			return;
		}
		if (!state.get().equals(CacheState.STARTED)) {
			String message = StringUtility.format("定时策略[{}]已经停止,拒绝接收元素[{}]", name, element);
			LOGGER.error(message);
			throw new CacheException(message);
		}
		// 保证异步操作与异步持久不会冲突
		Object cacheId = element.getCacheId();
		Lock writeLock = waitForLock.writeLock();
		try {
			writeLock.lock();
			PersistenceElement current = elements.get(cacheId);
			if (current == null) {
				current = element;
				elements.put(cacheId, current);
			} else {
				current.modify(element);
				if (current.isIgnore()) {
					// 忽略只清理elementMap,不清理elementQueue
					elements.remove(cacheId);
				}
			}
		} catch (CacheOperationException exception) {
			exceptionCount.incrementAndGet();
		} finally {
			writeLock.unlock();
		}
	}

	private void persist(Collection<PersistenceElement> elements) {
		synchronized (accessor) {
			// TODO 此处保证单元测试
		}
		for (PersistenceElement element : elements) {
			// 保证异步持久与异步操作不会冲突
			Object cacheId = element.getCacheId();
			try {
				Object instance = element.getCacheObject();
				synchronized (instance == null ? Thread.currentThread() : instance) {
					Lock writeLock = waitForLock.writeLock();
					try {
						writeLock.lock();
						if (element.isIgnore()) {
							LOGGER.error("此处不应该有忽略的元素[{}]", element);
							continue;
						}

						switch (element.getOperation()) {
						case CREATE:
							accessor.create(cacheClass, element.getCacheObject());
							createdCount.incrementAndGet();
							break;
						case DELETE:
							accessor.delete(cacheClass, element.getCacheId());
							deletedCount.incrementAndGet();
							break;
						case UPDATE:
							accessor.update(cacheClass, element.getCacheObject());
							updatedCount.incrementAndGet();
							break;
						default:
							LOGGER.error("未支持的元素类型[{}]", element);
							break;
						}
					} finally {
						waitSize.decrementAndGet();
						writeLock.unlock();
					}
				}
				if (monitor != null) {
					monitor.notifyOperate(element.getOperation(), element.getCacheId(), element.getCacheObject(), null);
				}
			} catch (Exception exception) {
				if (monitor != null && element != null) {
					monitor.notifyOperate(element.getOperation(), element.getCacheId(), element.getCacheObject(), exception);
				}
				exceptionCount.incrementAndGet();
				String message = StringUtility.format("定时策略[{}]处理元素[{}]时异常", new Object[] { name, element });
				LOGGER.error(message, exception);
			}
		}
	}

	@Override
	public synchronized void run() {
		while (true) {
			if (state.get().equals(CacheState.STOPPED)) {
				if (elements.isEmpty()) {
					break;
				}
			} else {
				persistTime = InstantUtility.getInstantAfter(cron, Instant.now());
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("定时策略[{}]的执行时间点为[{}]", name, LocalDateTime.ofInstant(persistTime, ZoneId.systemDefault()));
				}
				try {
					long wait = persistTime.toEpochMilli() - System.currentTimeMillis();
					if (wait > 0) {
						wait(wait);
					}
				} catch (InterruptedException exception) {
					if (LOGGER.isDebugEnabled()) {
						LOGGER.debug("定时策略[{}]立即执行[{}]", name, LocalDateTime.now());
					}
				}
			}
			ConcurrentHashMap<Object, PersistenceElement> elements = switchElements();
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("定时策略[{}]开始执行[{}]", new Object[] { name, LocalDateTime.now() });
			}
			persist(elements.values());
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("定时策略[{}]结束执行[{}],共更新[{}]条数据", new Object[] { name, LocalDateTime.now(), elements.size() });
			}
		}
	}

}
