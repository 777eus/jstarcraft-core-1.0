package com.jstarcraft.core.orm.berkeley;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

import com.jstarcraft.core.orm.berkeley.memorandum.FileMemorandumTestCase;
import com.jstarcraft.core.orm.berkeley.migration.MigrationTestCase;

@RunWith(Suite.class)
@SuiteClasses({
		// Berkeley访问器测试
		BerkeleyAccessorTestCase.class, BerkeleyMetadataTestCase.class,
		// Berleley文件备份测试
		FileMemorandumTestCase.class,
		// Berkeley迁移测试
		MigrationTestCase.class })
public class BerkeleyTestSuite {

}
