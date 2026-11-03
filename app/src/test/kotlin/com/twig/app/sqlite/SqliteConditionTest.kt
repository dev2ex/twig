package com.twig.app.sqlite

import org.junit.Assert.*
import org.junit.Test

class SqliteConditionTest {
    @Test fun `operators and typed operands round trip without accepting whole SQL statements`() {
        val examples = mapOf("= 18" to SqliteOperator.EQUAL, "<> 18" to SqliteOperator.NOT_EQUAL,
            "!= 18" to SqliteOperator.NOT_EQUAL, "== 18" to SqliteOperator.EQUAL,
            "> -2.5e1" to SqliteOperator.GREATER, ">= .5" to SqliteOperator.GREATER_EQUAL,
            "< +2" to SqliteOperator.LESS, "<= 9223372036854775807" to SqliteOperator.LESS_EQUAL,
            "like '%a%'" to SqliteOperator.LIKE, "not LIKE a%" to SqliteOperator.NOT_LIKE,
            "IS NULL" to SqliteOperator.IS_NULL, " is  not null " to SqliteOperator.IS_NOT_NULL)
        for ((input, operator) in examples) {
            val condition = SqliteCondition.parse(input)!!
            assertEquals(operator, condition.operator)
            assertEquals(condition, SqliteCondition.parse(condition.expression))
        }
        assertEquals('i', SqliteCondition.parse("= 18")!!.value!!.kind)
        assertEquals('r', SqliteCondition.parse("> 1.2345678901234567")!!.value!!.kind)
        assertEquals('t', SqliteCondition.parse("= '18'")!!.value!!.kind)
        assertEquals("O'Reilly", SqliteCondition.parse("= 'O''Reilly'")!!.value!!.text)
        assertEquals("", SqliteCondition.parse("= ''")!!.value!!.text)
        assertEquals("a b", SqliteCondition.parse("= a b")!!.value!!.text)
        assertEquals(SqliteOperator.IS_NULL, SqliteCondition.parse("= NULL")!!.operator)
        assertEquals(SqliteOperator.IS_NOT_NULL, SqliteCondition.parse("!= NULL")!!.operator)
        assertEquals("NULL", SqliteCondition.parse("= 'NULL'")!!.value!!.text)
        for (invalid in listOf("", "18", "category = 18", "SELECT 1", "LIKE", ">", "> NULL",
            "= 'unclosed", "= 'x' OR 1=1", "IS NULL; SELECT 1", "> 1e999", "IS", "GLOB '*'")) {
            assertNull(invalid, SqliteCondition.parse(invalid))
        }
    }

    @Test fun `keyword wildcards follow search rules but handwritten LIKE uses the exact pattern`() {
        assertEquals("%abc%", SqliteCondition.keyword("abc").value!!.text)
        assertEquals("abc%", SqliteCondition.keyword("abc%").value!!.text)
        assertEquals("%abc", SqliteCondition.keyword("%abc").value!!.text)
        assertEquals("%a_b%", SqliteCondition.keyword("a_b").value!!.text)
        assertEquals("abc", SqliteCondition.parse("LIKE abc")!!.value!!.text)
        assertEquals("abc", SqliteCondition.keyword("abc").keyword)
    }
}
