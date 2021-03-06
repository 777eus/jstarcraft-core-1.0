package com.jstarcraft.core.orm.hibernate;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.persistence.EmbeddedId;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Version;

import com.jstarcraft.core.cache.CacheObject;
import com.jstarcraft.core.orm.OrmMetadata;
import com.jstarcraft.core.utility.ClassUtility;
import com.jstarcraft.core.utility.ReflectionUtility;

/**
 * Hibernate元信息
 * 
 * @author Birdy
 */
@SuppressWarnings("rawtypes")
public class HibernateMetadata implements OrmMetadata {

	/** 实体名称 */
	private String ormName;
	/** 实体类型 */
	private Class ormClass;
	/** 主键名 */
	private String primaryName;
	/** 主键类型 */
	private Class primaryClass;
	/** 字段映射(名称-类型) */
	private Map<String, Class<?>> fields = new HashMap<>();
	/** 索引域名 */
	private Collection<String> indexNames = new HashSet<>();
	/** 版本号域名 */
	private String versionName;

	/**
	 * 构造方法
	 * 
	 * @param metadata
	 */
	HibernateMetadata(Class<?> clazz) {
		ormClass = clazz;
		ormName = clazz.getName();
		ReflectionUtility.doWithFields(ormClass, (field) -> {
			if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
				return;
			}
			if (field.isAnnotationPresent(Version.class)) {
				versionName = field.getName();
				return;
			}
			Class<?> type = ClassUtility.primitiveToWrapper(field.getType());
			if (String.class == type) {
				fields.put(field.getName(), type);
			} else if (type.isEnum()) {
				fields.put(field.getName(), type);
			} else if (Collection.class.isAssignableFrom(type) || type.isArray()) {
				fields.put(field.getName(), List.class);
			} else if (Date.class.isAssignableFrom(type)) {
				fields.put(field.getName(), Date.class);
			} else {
				fields.put(field.getName(), Map.class);
			}
			if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(EmbeddedId.class)) {
				primaryName = field.getName();
				primaryClass = type;
			}
		});
		Table table = clazz.getAnnotation(Table.class);
		if (table != null) {
			for (Index index : table.indexes()) {
				indexNames.add(index.columnList());
			}
		}
	}

	public String getOrmName() {
		return ormName;
	}

	public Map<String, Class<?>> getFields() {
		return fields;
	}

	public String getPrimaryName() {
		return primaryName;
	}

	public Collection<String> getIndexNames() {
		return indexNames;
	}

	public String getVersionName() {
		return versionName;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <K extends Serializable> Class<K> getPrimaryClass() {
		return primaryClass;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends CacheObject> Class<T> getOrmClass() {
		return ormClass;
	}

}
