package org.apache.spark.sql.execution.datasources

import com.sap.spark.util.TestUtils
import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.parser.{SapDDLParser, SapParserDialect, SapParserException}
import org.apache.spark.sql.sources.commands._
import org.apache.spark.sql.types._
import org.apache.spark.util.AnnotationParsingUtils
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{FunSuite, GivenWhenThen}

// scalastyle: off file.size.limit
class SapDDLParserSuite
  extends FunSuite
  with TableDrivenPropertyChecks
  with GivenWhenThen
  with AnnotationParsingUtils
  with Logging {

  val sqlParser = new SapParserDialect
  val ddlParser = new SapDDLParser(sqlParser.parse)

  test("DEEP DESCRIBE command") {
    val parsed = ddlParser.parse("DEEP DESCRIBE test")
    assert(parsed == UnresolvedDeepDescribe(UnresolvedRelation(TableIdentifier("test"))))
  }

  test("SHOW PARTITION FUNCTIONS command") {
    val parsed1 = ddlParser.parse("SHOW PARTITION FUNCTIONS USING com.sap.spark.dstest")
    val parsed2 = ddlParser.parse("SHOW PARTITION FUNCTIONS USING com.sap.spark.dstest " +
      "OPTIONS (foo \"bar\")")
    assertResult(
      ShowPartitionFunctionsUsingCommand("com.sap.spark.dstest", Map.empty))(parsed1)
    assertResult(
      ShowPartitionFunctionsUsingCommand("com.sap.spark.dstest", Map("foo" -> "bar")))(parsed2)
  }

  test("CREATE TABLE keeps the ddl statement in the options") {
    val ddl =
      s"""CREATE TABLE foo (a int, b int)
         |USING com.sap.spark.vora
         |OPTIONS (
         |)""".stripMargin

    val parsed = ddlParser.parse(ddl)

    assertResult(ddl)(parsed.asInstanceOf[CreateTableUsing].options("table_ddl"))
  }

  test("CREATE TABLE does not override a user provided ddl statement") {
    val ddl =
      s"""CREATE TABLE foo (a int, b int)
          |USING com.sap.spark.vora
          |OPTIONS (
          |table_ddl "bar"
          |)""".stripMargin

    val parsed = ddlParser.parse(ddl)

    assertResult("bar")(parsed.asInstanceOf[CreateTableUsing].options("table_ddl"))
  }

  test("OPTIONS (CONTENT) command") {
    val optionsPermutations = Table(
      """(
        |a "a",
        |b "b",
        |C "c"
        |)
      """.stripMargin,
      """(
        |A "a",
        |B "b",
        |c "c"
        |)
      """.stripMargin
    )

    forAll(optionsPermutations) { (opts) =>
      val statement = s"SHOW TABLES USING com.provider OPTIONS $opts"
      Given(s"statement $statement")

      val parsed = ddlParser.parse(statement)
      assertResult(
        ShowTablesUsingCommand(
          "com.provider",
          Map(
            "a" -> "a",
            "b" -> "b",
            "c" -> "c")))(parsed)
    }
  }

  val showDatasourceTablesPermutations = Table(
    ("sql", "provider", "options", "willFail"),
    ("SHOW DATASOURCETABLES USING com.provider", "com.provider", Map.empty[String, String], false),
    ("SHOW DATASOURCETABLES USING com.provider OPTIONS(key \"value\")",
      "com.provider", Map("key" -> "value"), false),
    ("SHOW DATASOURCETABLES", "", Map.empty[String, String], true)
  )

  val registerAllTablesCommandPermutations =
    Table(
      ("sql", "provider", "options", "ignoreConflicts", "allowExisting"),
      ("""REGISTER ALL TABLES IF NOT EXISTS USING provider.name OPTIONS() IGNORING CONFLICTS""",
        "provider.name", Map.empty[String, String], true, true),
      ("""REGISTER ALL TABLES IF NOT EXISTS USING provider.name OPTIONS(optionA "option")""",
        "provider.name", Map("optiona" -> "option"), false, true),
      ("""REGISTER ALL TABLES IF NOT EXISTS USING provider.name""",
        "provider.name", Map.empty[String, String], false, true),
      ("""REGISTER ALL TABLES IF NOT EXISTS USING provider.name IGNORING CONFLICTS""",
        "provider.name", Map.empty[String, String], true, true),
      ("""REGISTER ALL TABLES USING provider.name OPTIONS() IGNORING CONFLICTS""",
        "provider.name", Map.empty[String, String], true, false),
      ("""REGISTER ALL TABLES USING provider.name OPTIONS(optionA "option")""",
        "provider.name", Map("optiona" -> "option"), false, false),
      ("""REGISTER ALL TABLES USING provider.name""",
        "provider.name", Map.empty[String, String], false, false),
      ("""REGISTER ALL TABLES USING provider.name IGNORING CONFLICTS""",
        "provider.name", Map.empty[String, String], true, false)
    )

  test("REGISTER ALL TABLES command") {
    forAll(registerAllTablesCommandPermutations) {
      (sql: String,
       provider: String,
       options: Map[String, String],
       ignoreConflicts: Boolean,
       allowExisting: Boolean) =>
        Given(
          s"""provider: $provider,
             |options: $options,
             |ignoreConflicts: $ignoreConflicts,
             |allowExisting: $allowExisting""".stripMargin)
        val result = ddlParser.parse(sql)

        assertResult(
          RegisterAllTablesCommand(provider, options, ignoreConflicts, allowExisting))(result)
    }
  }

  val registerTableCommandPermutations =
    Table(
      ("sql", "table", "provider", "options", "ignoreConflicts", "allowExisting"),
      ("""REGISTER TABLE bla USING provider.name OPTIONS() IGNORING CONFLICTS""",
        "bla", "provider.name", Map.empty[String, String], true, false),
      ("""REGISTER TABLE bla USING provider.name OPTIONS(optionA "option")""",
        "bla", "provider.name", Map("optiona" -> "option"), false, false),
      ("""REGISTER TABLE bla USING provider.name""",
        "bla", "provider.name", Map.empty[String, String], false, false),
      ("""REGISTER TABLE bla USING provider.name IGNORING CONFLICTS""",
        "bla", "provider.name", Map.empty[String, String], true, false),
      ("""REGISTER TABLE IF NOT EXISTS bla USING provider.name OPTIONS() IGNORING CONFLICTS""",
        "bla", "provider.name", Map.empty[String, String], true, true),
      ("""REGISTER TABLE IF NOT EXISTS bla USING provider.name OPTIONS(optionA "option")""",
        "bla", "provider.name", Map("optiona" -> "option"), false, true),
      ("""REGISTER TABLE IF NOT EXISTS bla USING provider.name""",
        "bla", "provider.name", Map.empty[String, String], false, true),
      ("""REGISTER TABLE IF NOT EXISTS bla USING provider.name IGNORING CONFLICTS""",
        "bla", "provider.name", Map.empty[String, String], true, true)
    )

  test("REGISTER TABLE command") {
    forAll(registerTableCommandPermutations) {
      (sql: String,
       table: String,
       provider: String,
       options: Map[String, String],
       ignoreConflict: Boolean,
       allowExisting: Boolean) =>
        Given(
          s"""provider: $provider,
             |options: $options,
             |ignoreConflicts: $ignoreConflict
             |allowExisting: $allowExisting""".stripMargin)
        val result = ddlParser.parse(sql)
        assertResult(
          RegisterTableCommand(table, provider, options, ignoreConflict, allowExisting))(result)
    }
  }

  test("test DDL of Bug 90774") {
    val testTable = """
CREATE TEMPORARY TABLE testBaldat (field1 string, field2 string, field3 string,
  field4 string, field5 integer, field6 string, field7 integer)
USING com.sap.spark.vora
OPTIONS (
  tableName "testBaldat",
  files "/user/u1234/data.csv"
)"""
    ddlParser.parse(testTable, exceptionOnError = true)
    ddlParser.parse(testTable, exceptionOnError = false)
  }

  test("Replace 'paths' for vora datasource with files if needed (deprecation)") {
    val testTableVora = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.vora
      OPTIONS (
        tableName "testDep",
        paths "/user/u1234/data.csv"
    )"""
    assert(ddlParser.parse(testTableVora, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("files"))
    assert(!ddlParser.parse(testTableVora, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("paths"))

    val testTableVoraDS = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.vora.DefaultSource
      OPTIONS (
        tableName "testDep",
        paths "/user/u1234/data.csv"
    )"""
    assert(ddlParser.parse(testTableVoraDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("files"))
    assert(!ddlParser.parse(testTableVoraDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("paths"))
  }

  test("Replace 'path' for hana datasource with files if needed (deprecation)") {
    val testTableHana = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.hana
      OPTIONS (
        tableName "testDep",
        path "NAME"
    )"""
    assert(ddlParser.parse(testTableHana, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("tablepath"))
    assert(!ddlParser.parse(testTableHana, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("path"))

    val testTableHanaDS = """
      CREATE TABLE testDeprec (field1 string)
      USING com.sap.spark.hana.DefaultSource
      OPTIONS (
        tableName "testDep",
        path "/user/u1234/data.csv"
    )"""
    assert(ddlParser.parse(testTableHanaDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("tablepath"))
    assert(!ddlParser.parse(testTableHanaDS, exceptionOnError = true)
      .asInstanceOf[CreateTableUsing].options.contains("path"))
  }

  test("test simple CREATE TEMPORARY TABLE (Bug 90774)") {
    val testTable = """CREATE TEMPORARY TABLE tab001(a int)
      USING a.b.c.d
      OPTIONS (
        tableName "blaaa"
      )"""
    ddlParser.parse(testTable, exceptionOnError = true)
    ddlParser.parse(testTable, exceptionOnError = false)
  }

  /* Checks that the parse error position
   * corresponds to the syntax error position.
   *
   * Since the ddlParser falls back to the sqlParser
   * on error throwing the correct parse exception
   * is crucial. I.e., this test makes sure that not only
   * exception from the sqlParser is thrown on failure
   * but the one from the parser that consumed the most characters.
   */
  test("check reasonable parse errors") {

    val wrongSyntaxQueries = Array(
      ("CREATE TEMPORARY TABLE table001 (a1 int_, a2 int)", 1, 37),
      ("""CREATE TEMPORARY TABL____ table001 (a1 int, a2 int)
USING com.sap.spark.vora
OPTIONS (
  tableName "table001")""", 1, 18),
      ("""CREATE TEMPORARY TABLE table001 (a1 int, a2 int)
USIN_ com.sap.spark.vora
OPTIONS (
  tableName "table001")""", 2, 1),
      ("""CREATE TEMPORARY TABLE tab01(a int)
USING com.sap.spark.vora
OPTIONS (
  tableName "table001" """, 4, 24),
      ("SELCT * FROM table001", 1, 1),
      ("CREAT TABLE table001(a1 int)", 1, 1),
      ("", 1, 1),
      ("   ", 1, 4),
      ("\n\n\n\n", 5, 1),
      ("abcdefg", 1, 1)
    )

    for((query, line, col) <- wrongSyntaxQueries) {
      val vpe: SapParserException = intercept[SapParserException] {
        ddlParser.parse(query, exceptionOnError = false)
      }
      val expLine = vpe.line
      val expCol = vpe.column
      assert(expLine == line)
      assert(expCol == col)
    }
  }

  test("Parse correct CREATE TABLE statements with the PARTITION BY clause") {
    val testStatement1 = """CREATE TEMPORARY TABLE test1 (a integer, b string)
                        PARTITIONED BY example (a)
                        USING com.sap.spark.vora
                        OPTIONS (
                        tableName "test1",
                        files "/data.csv")"""
    val parsedStmt1 = ddlParser.parse(testStatement1)
    assert(parsedStmt1.isInstanceOf[CreateTablePartitionedByUsing])

    val ctp1 = parsedStmt1.asInstanceOf[CreateTablePartitionedByUsing]
    assert(ctp1.tableIdent.table == "test1")
    assert(ctp1.userSpecifiedSchema.isDefined)
    assert(ctp1.userSpecifiedSchema.get ==
      StructType(Seq(StructField("a", IntegerType, nullable = true),
        StructField("b", StringType, nullable = true))))
    assert(ctp1.partitioningFunc == "example")
    assert(ctp1.partitioningColumns == Seq("a"))
    assert(ctp1.provider == "com.sap.spark.vora")

    val testStatement2 = """CREATE TEMPORARY TABLE test1 (a integer, b string)
                        PARTITION BY example (a, b)
                        USING com.sap.spark.vora
                        OPTIONS (
                        tableName "test1",
                        files "/data.csv")"""
    val parsedStmt2 = ddlParser.parse(testStatement2)
    assert(parsedStmt2.isInstanceOf[CreateTablePartitionedByUsing])

    val ctp2 = parsedStmt2.asInstanceOf[CreateTablePartitionedByUsing]
    assert(ctp2.tableIdent.table == "test1")
    assert(ctp2.userSpecifiedSchema.isDefined)
    assert(ctp2.userSpecifiedSchema.get ==
      StructType(Seq(StructField("a", IntegerType, nullable = true),
        StructField("b", StringType, nullable = true))))
    assert(ctp2.partitioningFunc == "example")
    assert(ctp2.partitioningColumns == Seq("a", "b"))
    assert(ctp2.provider == "com.sap.spark.vora")


    val testStatement3 = """CREATE TEMPORARY TABLE test1 (a integer, b string, test float)
                        PARTITIONED BY example (test)
                        USING com.sap.spark.vora
                        OPTIONS (
                        tableName "test1",
                        files "/data.csv")"""
    val parsedStmt3 = ddlParser.parse(testStatement3)
    assert(parsedStmt3.isInstanceOf[CreateTablePartitionedByUsing])

    val ctp3 = parsedStmt3.asInstanceOf[CreateTablePartitionedByUsing]
    assert(ctp3.tableIdent.table == "test1")
    assert(ctp3.userSpecifiedSchema.isDefined)
    assert(ctp3.userSpecifiedSchema.get ==
      StructType(Seq(StructField("a", IntegerType, nullable = true),
        StructField("b", StringType, nullable = true),
        StructField("test", FloatType, nullable = true))))
    assert(ctp3.partitioningFunc == "example")
    assert(ctp3.partitioningColumns == Seq("test"))
    assert(ctp3.provider == "com.sap.spark.vora")
  }

  test("Do not parse incorrect CREATE TABLE statements with the PARTITION BY clause") {
    val invStatement = """CREATE TEMPORARY TABLE test1 (a integer, b string)
                       PARTITIONED BY example
                       USING com.sap.spark.vora
                       OPTIONS (
                       tableName "test1",
                       files "/data.csv")"""
    intercept[SapParserException](ddlParser.parse(invStatement))
  }

  test("Parse a correct CREATE PARTITION FUNCTION HASH statement without the PARTITIONS clause") {
    val testTable =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)
    assertResult(
        CreateHashPartitioningFunctionCommand(
        Map("discovery" -> "1.1.1.1"),
        "test",
        Seq(IntegerType, StringType),
        None,
        "com.sap.spark.vora"))(parsedStmt)
  }

  test("Parse a correct CREATE PARTITION FUNCTION HASH statement with the PARTITIONS clause") {
    val testTable =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)

    assertResult(
      CreateHashPartitioningFunctionCommand(
        Map("discovery" -> "1.1.1.1"),
        "test",
        Seq(IntegerType, StringType),
        Some(7), // scalastyle:ignore magic.number
        "com.sap.spark.vora"))(parsedStmt)
  }

  // scalastyle:off magic.number
  test("Parse a correct CREATE PARTITION FUNCTION RANGE statement with SPLITTERS") {
    val testTable1 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE SPLITTERS (5, 10, 15)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt1 = ddlParser.parse(testTable1)
    assertResult(
      CreateRangeSplitPartitioningFunctionCommand(
        Map("discovery" -> "1.1.1.1"),
        "test",
        IntegerType,
        Seq(5, 10, 15),
        rightClosed = false,
        "com.sap.spark.vora"))(parsedStmt1)

    val testTable2 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE SPLITTERS RIGHT CLOSED (5, 20)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt2 = ddlParser.parse(testTable2)
    assertResult(
      CreateRangeSplitPartitioningFunctionCommand(
        Map("discovery" -> "1.1.1.1"),
        "test",
        IntegerType,
        Seq(5, 20),
        rightClosed = true,
        "com.sap.spark.vora"))(parsedStmt2)
  }

  test("Parse a correct CREATE PARTITION FUNCTION RANGE statement with START/END") {
    val testTable1 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE START 5 END 20 STRIDE 2
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt1 = ddlParser.parse(testTable1)
    assertResult(
      CreateRangeIntervalPartitioningFunctionCommand(
        Map("discovery" -> "1.1.1.1"),
        "test",
        IntegerType,
        5,
        20,
        Left(2),
        "com.sap.spark.vora"))(parsedStmt1)

    val testTable2 =
      """CREATE PARTITION FUNCTION test (integer) AS RANGE START 5 END 25 PARTS 3
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val parsedStmt2 = ddlParser.parse(testTable2)
    assertResult(
      CreateRangeIntervalPartitioningFunctionCommand(
        Map("discovery" -> "1.1.1.1"),
        "test",
        IntegerType,
        5,
        25,
        Right(3),
        "com.sap.spark.vora"))(parsedStmt2)
  }
  // scalastyle:on magic.number

  test("Do not parse incorrect CREATE PARTITION FUNCTION statements") {
    val invStatement1 =
      """CREATE PARTITION FUNCTION (integer, string) AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """CREATE PARTITION FUNCTION 44test (integer,) AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))

    val invStatement3 =
      """CREATE PARTITION FUNCTION test AS HASH
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))

    val invStatement4 =
      """CREATE PARTITION FUNCTION test AS HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement4))

    val invStatement5 =
      """CREATE PARTITION FUNCTION test (integer, string) HASH PARTITIONS 7
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement5))

    val invStatement6 =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement6))

    val invStatement7 =
      """CREATE PARTITION FUNCTION test (integer, string) AS HASH
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement7))

    val invStatement8 =
      """CREATE PARTITION FUNCION test (integer, string) AS HASH
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement8))

    val invStatement9 =
      """CREATE PARTITION FUNCTION test () AS HASH
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex1 = intercept[DDLException](ddlParser.parse(invStatement9))
    assert(ex1.getMessage.contains("The hashing function argument list cannot be empty."))

    val invStatement10 =
      """CREATE PARTITION FUNCTION test AS RANGE SPLITTERS ("5", "10", "15")
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement10))

    val invStatement11 =
      """CREATE PARTITION FUNCTION test () AS RANGE SPLITTERS ()
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex4 = intercept[DDLException](ddlParser.parse(invStatement11))
    assert(ex4.getMessage.contains("The range function argument list cannot be empty."))

    val invStatement12 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE SPLITTERS (5, 10, 15)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex5 = intercept[DDLException](ddlParser.parse(invStatement12))
    assert(ex5.getMessage.contains("The range functions cannot have more than one argument."))

    val invStatement13 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE SPLIYTTERS (5, 10, 15)
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement13))

    val invStatement14 =
      """CREATE PARTITION FUNCTION test AS RANGE START 5 END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement14))

    val invStatement15 =
      """CREATE PARTITION FUNCTION test () AS RANGE START 5 END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex6 = intercept[DDLException](ddlParser.parse(invStatement15))
    assert(ex6.getMessage.contains("The range function argument list cannot be empty."))

    val invStatement16 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START 5 END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    val ex7 = intercept[DDLException](ddlParser.parse(invStatement16))
    assert(ex7.getMessage.contains("The range functions cannot have more than one argument."))

    val invStatement17 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START 5 END 10 STRdIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement17))

    val invStatement18 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START END 10 STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement18))

    val invStatement19 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START "DF" END STRIDE 1
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement19))

    val invStatement20 =
      """CREATE PARTITION FUNCTION test (integer, string) AS RANGE START "DF" END "ZZ"
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "1.1.1.1")
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement20))
  }

  test("Parse a correct DROP PARTITION FUNCTION statement") {
    val testTable =
      """DROP PARTITION FUNCTION test
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "local")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)
    assertResult(
      DropPartitioningFunctionCommand(
        Map("discovery" -> "local"),
        "test",
        allowNotExisting = false,
        "com.sap.spark.vora"))(parsedStmt)
  }

  test("Parse a correct DROP PARTITION FUNCTION IF EXISTS statement") {
    val testTable =
      """DROP PARTITION FUNCTION IF EXISTS test
        |USING com.sap.spark.vora
        |OPTIONS (
        |discovery "local")
      """.stripMargin
    val parsedStmt = ddlParser.parse(testTable)
    assertResult(
      DropPartitioningFunctionCommand(
        Map("discovery" -> "local"),
        "test",
        allowNotExisting = true,
        "com.sap.spark.vora"))(parsedStmt)
  }

  test("Do not parse incorrect DROP PARTITION FUNCTION statements") {
    val invStatement1 =
      """DROP FUNCTION test
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """DROP PARTITION FUNCTION
        |USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))

    val invStatement3 =
      """DROP PARTITION FUNCTION test
        |USG com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))

    val invStatement4 =
      """DROP PARTITION FUNCTION test
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement4))
  }

  test("Handle incorrect DROP VIEW statements") {
    val invStatement1 =
      """DROP VIE v USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement3 =
      """DROP VIEW v USIN com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement3))
  }

  test("Handle correct DROP VIEW statements") {
    val statement = ddlParser.parse("DROP VIEW v1")

    assertResult(
      UnresolvedDropCommand(
        ViewTarget,
        allowNotExisting = false,
        TableIdentifier("v1"),
        cascade = false))(statement)
  }

  test("Parse correct SHOW TABLES USING statement") {
    val statement = """SHOW TABLES
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[ShowTablesUsingCommand])

    val actual = parsed.asInstanceOf[ShowTablesUsingCommand]
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Handle incorrect SHOW TABLES USING statement") {
    val invStatement1 =
      """SHOW TBLES USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """SHOW TABLES USNG com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))
  }

  test("Parse correct DESCRIBE TABLE USING statement") {
    val statement = """DESCRIBE TABLE t1
                      |USING com.sap.spark.vora
                      |OPTIONS(discovery "1.1.1.1")""".stripMargin

    val parsed = ddlParser.parse(statement)
    assert(parsed.isInstanceOf[DescribeTableUsingCommand])

    val actual = parsed.asInstanceOf[DescribeTableUsingCommand]
    assertResult("com.sap.spark.vora")(actual.provider)
    assertResult(Map[String, String]("discovery" -> "1.1.1.1"))(actual.options)
  }

  test("Handle incorrect DESCRIBE TABLE USING statement") {
    val invStatement1 =
      """DESCRIBE TBLE t1 USING com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement1))

    val invStatement2 =
      """DESCRIBE TABLE t1 UZIN com.sap.spark.vora
      """.stripMargin
    intercept[SapParserException](ddlParser.parse(invStatement2))
  }

  test("test engine DDL from file") {
    val tests: List[(String, String, String)] =
      TestUtils.parsePTestFile("/EngineDDL.ptest")

    tests.zipWithIndex.foreach {
      case ((query, parsed, expect), i) =>
        logInfo(s"test $i runs query: $query")
        val queryWithDs =
          query.contains("using test.data.source") match {
            case false => query + " using test.data.source"
            case _ => query
          }
        if (expect == "valid") {
          val result = ddlParser.parse(queryWithDs)
          assert(result.toString.trim == parsed)
        } else {
          intercept[SapParserException](ddlParser.parse(queryWithDs))
        }
    }
  }

  test("Table with COMMENT as column name is allowed") {
    val statement = "CREATE TABLE foo (name string, comment string) USING com.sap.spark.vora"
    val parsed =
      ddlParser.parse(statement)

    assertResult(
      CreateTableUsing(
        TableIdentifier("foo"),
        Some(
          StructType(
            StructField("name", StringType) ::
            StructField("comment", StringType) :: Nil)),
        "com.sap.spark.vora",
        temporary = false,
        Map("table_ddl" -> statement),
        allowExisting = false,
        managedIfNoPath = false))(parsed)
  }

  test("It is not possible to create a table with a reserved word as column name") {
    intercept[SapParserException] {
      ddlParser.parse("CREATE TABLE foo (null string, all string) USING com.sap.spark.vora")
    }
  }
}
// scalastyle:on
