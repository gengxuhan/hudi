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

package org.apache.spark.sql.hudi

import org.apache.hudi.common.table.HoodieTableMetaClient
import org.apache.spark.sql.Row

class TestMergeIntoTable2 extends TestHoodieSqlBase {

  test("Test MergeInto for MOR table 2") {
    withTempDir { tmp =>
      val tableName = generateTableName
      // Create a mor partitioned table.
      spark.sql(
        s"""
           | create table $tableName (
           |  id int,
           |  name string,
           |  price double,
           |  ts long,
           |  dt string
           | ) using hudi
           | options (
           |  type = 'mor',
           |  primaryKey = 'id',
           |  preCombineField = 'ts'
           | )
           | partitioned by(dt)
           | location '${tmp.getCanonicalPath}'
         """.stripMargin)
      // Insert data which matched insert-condition.
      spark.sql(
        s"""
           | merge into $tableName as t0
           | using (
           |  select 1 as id, 'a1' as name, 10 as price, 1000 as ts, '2021-03-21' as dt
           | ) as s0
           | on t0.id = s0.id
           | when not matched and s0.id % 2 = 1 then insert *
         """.stripMargin
      )
      checkAnswer(s"select id,name,price,dt from $tableName")(
        Seq(1, "a1", 10, "2021-03-21")
      )

      // Insert data which not matched insert-condition.
      spark.sql(
        s"""
           | merge into $tableName as t0
           | using (
           |  select 2 as id, 'a2' as name, 10 as price, 1000 as ts, '2021-03-21' as dt
           | ) as s0
           | on t0.id = s0.id
           | when not matched and s0.id % 2 = 1 then insert *
         """.stripMargin
      )
      checkAnswer(s"select id,name,price,dt from $tableName")(
        Seq(1, "a1", 10, "2021-03-21")
      )

      // Update data which not matched update-condition
      spark.sql(
        s"""
           | merge into $tableName as t0
           | using (
           |  select 1 as id, 'a1' as name, 11 as price, 1000 as ts, '2021-03-21' as dt
           | ) as s0
           | on t0.id = s0.id
           | when matched and s0.id % 2 = 0 then update set *
           | when matched and s0.id % 3 = 2 then delete
           | when not matched then insert *
         """.stripMargin
      )
      checkAnswer(s"select id,name,price,dt from $tableName")(
        Seq(1, "a1", 10, "2021-03-21")
      )

      // Update data which matched update-condition
      spark.sql(
        s"""
           | merge into $tableName as t0
           | using (
           |  select 1 as id, 'a1' as name, 11 as price, 1000 as ts, '2021-03-21' as dt
           | ) as s0
           | on t0.id = s0.id
           | when matched and s0.id % 2 = 1 then update set id = s0.id, name = s0.name,
           |  price = s0.price * 2, ts = s0.ts, dt = s0.dt
           | when not matched then insert (id,name,price,ts,dt) values(s0.id, s0.name, s0.price, s0.ts, s0.dt)
         """.stripMargin
      )
      checkAnswer(s"select id,name,price,dt from $tableName")(
        Seq(1, "a1", 22, "2021-03-21")
      )

      // Delete data which matched update-condition
      spark.sql(
        s"""
           | merge into $tableName as t0
           | using (
           |  select 1 as id, 'a1' as name, 11 as price, 1000 as ts, '2021-03-21' as dt
           | ) as s0
           | on t0.id = s0.id
           | when matched and s0.id % 2 = 0 then update set id = s0.id, name = s0.name,
           |  price = s0.price * 2, ts = s0.ts, dt = s0.dt
           | when matched and s0.id % 2 = 1 then delete
           | when not matched then insert (id,name,price,ts,dt) values(s0.id, s0.name, s0.price, s0.ts, s0.dt)
         """.stripMargin
      )
      checkAnswer(s"select count(1) from $tableName")(
        Seq(0)
      )

      checkException(
        s"""
           | merge into $tableName as t0
           | using (
           |  select 1 as id, 'a1' as name, 10 as price, 1000 as ts, '2021-03-21' as dt
           | ) as s0
           | on t0.id = s0.id
           | when matched and s0.id % 2 = 1 then update set id = s0.id, name = s0.name,
           |  price = s0.price + t0.price, ts = s0.ts, dt = s0.dt
         """.stripMargin
      )("assertion failed: Target table's field(price) cannot be the right-value of the update clause for MOR table.")
    }
  }

  test("Test Merge Into CTAS Table") {
    withTempDir { tmp =>
      val tableName = generateTableName
      spark.sql(
        s"""
           |create table $tableName using hudi
           |options(primaryKey = 'id')
           |location '${tmp.getCanonicalPath}'
           |as
           |select 1 as id, 'a1' as name
           |""".stripMargin
      )
      val metaClient = HoodieTableMetaClient.builder()
        .setBasePath(tmp.getCanonicalPath)
        .setConf(spark.sessionState.newHadoopConf())
        .build()
      // check record key in hoodie.properties
      assertResult("id")(metaClient.getTableConfig.getRecordKeyFields.get().mkString(","))

      spark.sql(
        s"""
           |merge into $tableName h0
           |using (
           | select 1 as s_id, 'a1_1' as name
           |) s0
           |on h0.id = s0.s_id
           |when matched then update set *
           |""".stripMargin
      )
      checkAnswer(s"select id, name from $tableName")(
        Seq(1, "a1_1")
      )
    }
  }

  test("Test Merge With Complex Data Type") {
    withTempDir { tmp =>
      val tableName = generateTableName
      spark.sql(
        s"""
           | create table $tableName (
           |  id int,
           |  name string,
           |  s_value struct<f0: int, f1: string>,
           |  a_value array<string>,
           |  m_value map<string, string>,
           |  ts long
           | ) using hudi
           | options (
           |  type = 'mor',
           |  primaryKey = 'id',
           |  preCombineField = 'ts'
           | )
           | location '${tmp.getCanonicalPath}'
         """.stripMargin)

      spark.sql(
        s"""
           |merge into $tableName h0
           |using (
           |select
           |  1 as id,
           |  'a1' as name,
           |  struct(1, '10') as s_value,
           |  split('a0,a1', ',') as a_value,
           |  map('k0', 'v0') as m_value,
           |  1000 as ts
           |) s0
           |on h0.id = s0.id
           |when not matched then insert *
           |""".stripMargin)

      checkAnswer(s"select id, name, s_value, a_value, m_value, ts from $tableName")(
        Seq(1, "a1", Row(1, "10"), Seq("a0", "a1"), Map("k0" -> "v0"), 1000)
      )
      // update value
      spark.sql(
        s"""
           |merge into $tableName h0
           |using (
           |select
           |  1 as id,
           |  'a1' as name,
           |  struct(1, '12') as s_value,
           |  split('a0,a1,a2', ',') as a_value,
           |  map('k1', 'v1') as m_value,
           |  1000 as ts
           |) s0
           |on h0.id = s0.id
           |when matched then update set *
           |when not matched then insert *
           |""".stripMargin)
      checkAnswer(s"select id, name, s_value, a_value, m_value, ts from $tableName")(
        Seq(1, "a1", Row(1, "12"), Seq("a0", "a1", "a2"), Map("k1" -> "v1"), 1000)
      )
    }
  }

  test("Test column name matching for insert * and update set *") {
    withTempDir { tmp =>
      val tableName = generateTableName
      // Create table
      spark.sql(
        s"""
           |create table $tableName (
           |  id int,
           |  name string,
           |  price double,
           |  ts long,
           |  dt string
           |) using hudi
           | location '${tmp.getCanonicalPath}/$tableName'
           | options (
           |  primaryKey ='id',
           |  preCombineField = 'ts'
           | )
       """.stripMargin)

      // Insert data to source table
      spark.sql(s"insert into $tableName select 1, 'a1', 1, 10, '2021-03-21'")
      checkAnswer(s"select id, name, price, ts, dt from $tableName")(
        Seq(1, "a1", 1.0, 10, "2021-03-21")
      )

      // Test the order of column types in sourceTable is similar to that in targetTable
      spark.sql(
        s"""
           |merge into $tableName as t0
           |using (
           |  select 1 as id, '2021-05-05' as dt, 1002 as ts, 97 as price, 'a1' as name union all
           |  select 1 as id, '2021-05-05' as dt, 1003 as ts, 98 as price, 'a2' as name union all
           |  select 2 as id, '2021-05-05' as dt, 1001 as ts, 99 as price, 'a3' as name
           | ) as s0
           |on t0.id = s0.id
           |when matched then update set *
           |when not matched then insert *
           |""".stripMargin)
      checkAnswer(s"select id, name, price, ts, dt from $tableName")(
        Seq(1, "a2", 98.0, 1003, "2021-05-05"),
        Seq(2, "a3", 99.0, 1001, "2021-05-05")
      )
      // Test the order of the column types of sourceTable is different from the column types of targetTable
      spark.sql(
        s"""
           |merge into $tableName as t0
           |using (
           |  select 1 as id, 'a1' as name, 1004 as ts, '2021-05-05' as dt, 100 as price union all
           |  select 2 as id, 'a5' as name, 1000 as ts, '2021-05-05' as dt, 101 as price union all
           |  select 3 as id, 'a3' as name, 1000 as ts, '2021-05-05' as dt, 102 as price
           | ) as s0
           |on t0.id = s0.id
           |when matched then update set *
           |when not matched then insert *
           |""".stripMargin)
      checkAnswer(s"select id, name, price, ts, dt from $tableName")(
        Seq(1, "a1", 100.0, 1004, "2021-05-05"),
        Seq(2, "a3", 99.0, 1001, "2021-05-05"),
        Seq(3, "a3", 102.0, 1000, "2021-05-05")
      )

      // Test an extra input field 'flag'
      spark.sql(
        s"""
           |merge into $tableName as t0
           |using (
           |  select 1 as id, 'a6' as name, 1006 as ts, '2021-05-05' as dt, 106 as price, '0' as flag union all
           |  select 4 as id, 'a4' as name, 1000 as ts, '2021-05-06' as dt, 100 as price, '1' as flag
           | ) as s0
           |on t0.id = s0.id
           |when matched and flag = '1' then update set *
           |when not matched and flag = '1' then insert *
           |""".stripMargin)
      checkAnswer(s"select id, name, price, ts, dt from $tableName")(
        Seq(1, "a1", 100.0, 1004, "2021-05-05"),
        Seq(2, "a3", 99.0, 1001, "2021-05-05"),
        Seq(3, "a3", 102.0, 1000, "2021-05-05"),
        Seq(4, "a4", 100.0, 1000, "2021-05-06")
      )
    }
  }

  test("Test MergeInto For Source Table With Column Aliases") {
    withTempDir { tmp =>
      val tableName = generateTableName
      // Create table
      spark.sql(
        s"""
           |create table $tableName (
           |  id int,
           |  name string,
           |  price double,
           |  ts long
           |) using hudi
           | location '${tmp.getCanonicalPath}/$tableName'
           | options (
           |  primaryKey ='id',
           |  preCombineField = 'ts'
           | )
       """.stripMargin)

      // Merge with an extra input field 'flag' (insert a new record)
      val mergeSql =
        s"""
           | merge into $tableName
           | using (
           |  select 1, 'a1', 10, 1000, '1'
           | ) s0(id,name,price,ts,flag)
           | on s0.id = $tableName.id
           | when matched and flag = '1' then update set
           | id = s0.id, name = s0.name, price = s0.price, ts = s0.ts
           | when not matched and flag = '1' then insert *
           |""".stripMargin

      if (HoodieSqlUtils.isSpark3) {
        checkException(mergeSql)(
            "\nColumns aliases are not allowed in MERGE.(line 5, pos 5)\n\n" +
            "== SQL ==\n\r\n" +
            s" merge into $tableName\r\n" +
            " using (\r\n" +
            "  select 1, 'a1', 10, 1000, '1'\r\n" +
            " ) s0(id,name,price,ts,flag)\r\n" +
            "-----^^^\n" +
            s" on s0.id = $tableName.id\r\n" +
            " when matched and flag = '1' then update set\r\n" +
            " id = s0.id, name = s0.name, price = s0.price, ts = s0.ts\r\n" +
            " when not matched and flag = '1' then insert *\r\n"
        )
      } else {
        spark.sql(mergeSql)
        checkAnswer(s"select id, name, price, ts from $tableName")(
          Seq(1, "a1", 10.0, 1000)
        )
      }
    }
  }

}
