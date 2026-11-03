package com.twig.app.sqlite

/** Fixed operators and bound operands; handwritten filters never become executable SQL. */
enum class SqliteOperator(val sql: String, val unary: Boolean = false) {
    EQUAL("="), NOT_EQUAL("!="), GREATER(">"), GREATER_EQUAL(">="), LESS("<"), LESS_EQUAL("<="),
    LIKE("LIKE"), NOT_LIKE("NOT LIKE"), IS_NULL("IS NULL", true), IS_NOT_NULL("IS NOT NULL", true)
}

data class SqliteCondition private constructor(
    val operator: SqliteOperator,
    val value: SqliteCell? = null,
    val keyword: String? = null,
) {
    val expression: String get() = operator.sql + (value?.let {
        " " + if (it.kind == 't') "'${it.text.replace("'", "''")}'" else it.text
    } ?: "")

    internal fun predicate(name: String, args: MutableList<String>): String {
        if (operator.unary) return "$name ${operator.sql}"
        val operand = checkNotNull(value)
        args.add(operand.text)
        val parameter = "?${args.size}"
        if (operator == SqliteOperator.LIKE || operator == SqliteOperator.NOT_LIKE)
            return "(typeof($name) != 'blob' AND CAST($name AS TEXT) ${operator.sql} $parameter)"
        // Do not treat nonnumeric strings as greater than every number in mixed columns.
        val type = when (operand.kind) { 'i' -> "INTEGER"; 'r' -> "REAL"; else -> "TEXT" }
        val kind = if (operand.kind == 't') "typeof($name) = 'text'" else "typeof($name) IN ('integer','real')"
        return "($kind AND $name ${operator.sql} CAST($parameter AS $type))"
    }

    companion object {
        private val unary = Regex("^IS\\s+(NOT\\s+)?NULL$", RegexOption.IGNORE_CASE)
        private val binary = Regex("^(NOT\\s+LIKE\\b|LIKE\\b|>=|<=|<>|!=|==|=|>|<)\\s*(.*)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val quoted = Regex("^'(?:[^']|'')*'$")
        private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

        fun keyword(text: String) = SqliteCondition(SqliteOperator.LIKE,
            SqliteCell.text(if ('%' in text) text else "%$text%"), keyword = text)

        fun parse(input: String): SqliteCondition? {
            val text = input.trim()
            unary.matchEntire(text)?.let {
                return SqliteCondition(if (it.groupValues[1].isEmpty()) SqliteOperator.IS_NULL else SqliteOperator.IS_NOT_NULL)
            }
            val match = binary.matchEntire(text) ?: return null
            val token = match.groupValues[1].uppercase().replace(Regex("\\s+"), " ")
            val op = when (token) {
                "<>", "!=" -> SqliteOperator.NOT_EQUAL
                "==" -> SqliteOperator.EQUAL
                else -> SqliteOperator.entries.firstOrNull { it.sql == token } ?: return null
            }
            val raw = match.groupValues[2].trim()
            if (raw.isEmpty()) return null
            if (raw.equals("NULL", true)) return when (op) {
                SqliteOperator.EQUAL -> SqliteCondition(SqliteOperator.IS_NULL)
                SqliteOperator.NOT_EQUAL -> SqliteCondition(SqliteOperator.IS_NOT_NULL)
                else -> null
            }
            val value = when {
                raw.startsWith("'") -> {
                    if (!quoted.matches(raw)) return null
                    SqliteCell.text(raw.substring(1, raw.length - 1).replace("''", "'"))
                }
                raw.startsWith('"') -> return null
                op == SqliteOperator.LIKE || op == SqliteOperator.NOT_LIKE -> SqliteCell.text(raw)
                number.matches(raw) -> {
                    val integer = raw.toLongOrNull()
                    if (integer != null) SqliteCell('i', integer.toString()) else {
                        if (raw.toDoubleOrNull()?.isFinite() != true) return null
                        SqliteCell('r', raw)
                    }
                }
                else -> SqliteCell.text(raw)
            }
            return SqliteCondition(op, value)
        }
    }
}
