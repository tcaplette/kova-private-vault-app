package com.app.vault

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The calculator cover's evaluator — P2-24.
 *
 * **Scientific for the question a person asks out loud.** Every device in the test
 * matrix ships a four-function calculator, so a second one invites *"why do you have
 * two"*; parentheses, trigonometry with a degrees/radians selector, logarithms, roots
 * and powers answer it without anybody having to. That set and no more — no memory
 * registers, and **no history tape**, the second of which is a security decision as
 * much as a design one: a tape records what was typed, and what was typed is the
 * trigger.
 *
 * **Precedence climbing, and no dependency.** Android has no `ScriptEngine` and the
 * Kotlin stdlib has no expression parser, so this is written rather than pulled in —
 * rung six honestly reached, not a library avoided for sport.
 *
 * **`Double` throughout.** `BigDecimal` buys nothing once `sin` is on the keypad, and
 * it would still not make `0.1 + 0.2` print `0.3`. [format] is what does that.
 *
 * Pure: no Android import, no logging, no state between evaluations. `CoverScanTest`
 * asserts the first two.
 */
object Calculator {

    /** Which unit the trigonometric functions read. */
    enum class Angle { Degrees, Radians }

    /** A token as the keypad produces it. The parser never sees a raw string. */
    sealed interface Token {
        data class Num(val value: Double) : Token
        data class Op(val symbol: Char) : Token
        data class Fn(val name: String) : Token

        /**
         * A **postfix** operator — `x²`, `1/x`, `x!`, `%` — P2-25.
         *
         * Its own token rather than a [Fn], because a prefix function takes a
         * parenthesis and these take what is already to their left. It binds tighter
         * than everything, including `^` and the unary minus, so `-3²` is `-9` and not
         * `9` — which is what every scientific calculator prints.
         */
        data class Post(val name: String) : Token

        /**
         * `π` or `e` — P2-25.
         *
         * A token rather than the digits of their value pushed into the entry buffer,
         * which is what P2-24 did. `π` typed as `3.14159265359` is a calculator that
         * cannot show you `π`, and every one on either store shows you `π`.
         */
        data class Const(val name: String) : Token
        data object Open : Token
        data object Close : Token
    }

    /** Thrown for anything the keypad should not have been able to produce. */
    class Malformed(message: String) : IllegalArgumentException(message)

    /** Division by zero, reported rather than returned as an infinity. */
    class DividedByZero : ArithmeticException("division by zero")

    /**
     * A result that does not exist — `ln(-1)`, `√(-1)`, `asin(2)`, `(-1)!` — P2-25.
     *
     * Distinct from [DividedByZero] and from [Malformed] because a calculator says
     * three different things about three different failures. One `Error` covering all
     * of them is a debug string, and a debug string on a cover is a tell.
     */
    class Undefined : ArithmeticException("undefined")

    /** A result too large for a `Double`. `9^9^9`, and `171!`. */
    class Overflow : ArithmeticException("overflow")

    /**
     * The prefix functions, each of which the keypad follows with an open parenthesis.
     *
     * P2-25 adds the five the `INV` chip reaches — the inverse trigonometry and the
     * two exponentials — and nothing else. `sqrt` stays a function; `x²` does not
     * become one, because it is postfix.
     */
    private val functions = setOf(
        "sin", "cos", "tan", "ln", "log", "sqrt",
        "asin", "acos", "atan", "exp", "pow10",
    )

    /** The postfix operators, for the keypad's own check. */
    private val postfix = setOf("sqr", "inv", "fact", "pct")

    /**
     * Precedence for the **binary** operators only.
     *
     * `^` is deliberately absent. It is right-associative *and* binds tighter than a
     * unary minus on its left while looser than one on its right, which no single
     * precedence number expresses — so it lives in [Cursor.power] instead. Treating
     * it as a fourth precedence level makes `-2^2` evaluate to `4`; a scientific
     * calculator prints `-4`.
     */
    private fun precedence(symbol: Char): Int = when (symbol) {
        '+', '-' -> 1
        '*', '/' -> 2
        else -> 0
    }

    /**
     * Evaluate a token stream.
     *
     * Precedence climbing over an explicit cursor: the recursive-descent shape reads
     * closest to the grammar, and the grammar is four lines. A shunting-yard would be
     * the same length and would need a second stack to say the same thing.
     */
    fun evaluate(tokens: List<Token>, angle: Angle = Angle.Radians): Double {
        if (tokens.isEmpty()) throw Malformed("empty expression")
        val cursor = Cursor(tokens, angle)
        val value = cursor.expression(0)
        if (!cursor.done) throw Malformed("trailing tokens")
        // The one place a non-finite result is turned into a named cause. Every
        // arithmetic path in this file passes through here, so [format] never has to
        // decide what a NaN means — and the screen is told which of the two it was.
        return finite(value)
    }

    /** [Undefined] for a NaN, [Overflow] for an infinity, the value otherwise. */
    private fun finite(value: Double): Double = when {
        value.isNaN() -> throw Undefined()
        value.isInfinite() -> throw Overflow()
        else -> value
    }

    private class Cursor(val tokens: List<Token>, val angle: Angle) {
        var at = 0
        val done get() = at >= tokens.size
        fun peek(): Token? = tokens.getOrNull(at)
        fun take(): Token = tokens.getOrNull(at++) ?: throw Malformed("expression ends early")

        /**
         * A value, then any binary operator binding at least as tightly as
         * [minPrecedence]. Left-associative, so `10 - 3 - 2` is `5` and not `9`.
         */
        fun expression(minPrecedence: Int): Double {
            var left = unary()
            while (true) {
                val op = peek()
                if (op !is Token.Op) break
                val p = precedence(op.symbol)
                if (p == 0 || p < minPrecedence) break
                at++
                left = apply(op.symbol, left, expression(p + 1))
            }
            return left
        }

        /**
         * Unary minus, binding **looser than `^` on its left**.
         *
         * `-unary()` and not `-atom()`: the latter negates the base before the
         * exponent is applied, so `-2^2` comes out `4`. Every scientific calculator
         * prints `-4`, and a cover that disagrees with the one already on the phone
         * is a cover somebody looks at twice.
         */
        fun unary(): Double {
            val token = peek()
            if (token is Token.Op && token.symbol == '-') {
                at++
                return -unary()
            }
            return power()
        }

        /**
         * `^`, right-associative, with a unary right-hand side.
         *
         * Right-associative so `2^3^2` is `2^9`. The exponent is [unary] rather than
         * [power] so `2^-3` parses — a minus is allowed *after* the caret even though
         * one before the base binds looser than the caret itself.
         */
        fun power(): Double {
            val base = atom()
            val op = peek()
            if (op is Token.Op && op.symbol == '^') {
                at++
                return base.pow(unary())
            }
            return base
        }

        /**
         * A value, then every postfix operator stacked on it.
         *
         * Consumed here rather than at a precedence level of their own, because
         * postfix binds tighter than anything else a keypad can produce — `3²!` is
         * `(3²)!` and there is no reading where it is not.
         */
        fun atom(): Double {
            var value = base()
            while (true) {
                val token = peek()
                if (token !is Token.Post) break
                at++
                value = post(token.name, value)
            }
            return value
        }

        fun base(): Double = when (val token = take()) {
            is Token.Num -> token.value
            is Token.Const -> when (token.name) {
                "pi" -> PI_VALUE
                "e" -> E_VALUE
                else -> throw Malformed("unknown constant ${token.name}")
            }
            is Token.Open -> expression(0).also {
                if (take() !is Token.Close) throw Malformed("unclosed parenthesis")
            }
            is Token.Fn -> {
                if (take() !is Token.Open) throw Malformed("${token.name} needs a parenthesis")
                val argument = expression(0)
                if (take() !is Token.Close) throw Malformed("unclosed parenthesis")
                call(token.name, argument, angle)
            }
            else -> throw Malformed("expected a value, found $token")
        }
    }

    private fun apply(symbol: Char, left: Double, right: Double): Double = when (symbol) {
        '+' -> left + right
        '-' -> left - right
        '*' -> left * right
        '/' -> if (right == 0.0) throw DividedByZero() else left / right
        '^' -> left.pow(right)
        else -> throw Malformed("unknown operator $symbol")
    }

    /**
     * The prefix functions.
     *
     * **Every domain is checked before the call, not after.** `ln(-1)` returns a NaN
     * rather than throwing, and a NaN that reaches the display as "Error" tells the
     * user nothing; caught here it becomes [Undefined], which is what a calculator
     * says. The inverse trigonometric functions return an angle, so they leave in
     * whichever unit the mode is in — the mirror of the conversion on the way in.
     */
    private fun call(name: String, argument: Double, angle: Angle): Double {
        val radians = if (angle == Angle.Degrees) argument * PI / 180.0 else argument
        fun out(value: Double) = if (angle == Angle.Degrees) value * 180.0 / PI else value
        return when (name) {
            "sin" -> sin(radians)
            "cos" -> cos(radians)
            "tan" -> tan(radians)
            "asin" -> if (abs(argument) > 1.0) throw Undefined() else out(asin(argument))
            "acos" -> if (abs(argument) > 1.0) throw Undefined() else out(acos(argument))
            "atan" -> out(atan(argument))
            "ln" -> if (argument <= 0.0) throw Undefined() else ln(argument)
            "log" -> if (argument <= 0.0) throw Undefined() else log10(argument)
            "sqrt" -> if (argument < 0.0) throw Undefined() else sqrt(argument)
            "exp" -> exp(argument)
            "pow10" -> 10.0.pow(argument)
            else -> throw Malformed("unknown function $name")
        }
    }

    /**
     * The postfix operators — P2-25.
     *
     * **Factorial is integer-only and stops at 170.** `171!` overflows a `Double`, and
     * a factorial of a fraction is the gamma function, which is not on this keypad.
     * Both refusals are [Undefined] rather than a silent infinity.
     *
     * `%` divides by a hundred and does nothing cleverer. The contextual reading —
     * `200 + 10%` meaning `220` — disagrees with itself across the calculators that
     * implement it, and a cover that disagrees with the one already on the phone is
     * the thing this whole screen exists to avoid.
     */
    private fun post(name: String, value: Double): Double = when (name) {
        "sqr" -> finite(value * value)
        "inv" -> if (value == 0.0) throw DividedByZero() else 1.0 / value
        "pct" -> value / 100.0
        "fact" -> {
            if (value < 0.0 || value != value.roundToLong().toDouble()) throw Undefined()
            if (value > 170.0) throw Overflow()
            var product = 1.0
            for (i in 2..value.roundToLong()) product *= i
            product
        }
        else -> throw Malformed("unknown postfix $name")
    }

    /** Whether [name] is a function this evaluator knows — the keypad's own check. */
    fun isFunction(name: String): Boolean = name in functions

    /** Whether [name] is a postfix operator this evaluator knows. */
    fun isPostfix(name: String): Boolean = name in postfix

    /**
     * How many significant figures the display keeps.
     *
     * **Twelve, and this is not cosmetic.** `Double` cannot represent `0.1 + 0.2`
     * exactly, so an unrounded display reads `0.30000000000000004` — which is the
     * single most likely thing to make somebody look twice at this app and decide it
     * is not the calculator it claims to be. Twelve keeps every result a person will
     * produce on a keypad and discards exactly the noise.
     */
    private const val SIGNIFICANT = 12

    /**
     * Render a result the way a calculator does.
     *
     * Whole numbers lose their decimal point, trailing zeros go, very large and very
     * small magnitudes fall back to exponent form rather than printing forty digits,
     * and a non-finite result reads as an error rather than as `Infinity`.
     */
    fun format(value: Double): String {
        // Non-finite values are named causes, not display strings — see [finite].
        // P2-24 returned "Error" from here for all three; P2-25 does not, because a
        // calculator that says the same word about a division by zero and about an
        // overflow is a calculator somebody stops trusting.
        finite(value)
        if (value == 0.0) return "0"

        val rounded = value.toBigDecimal().round(
            java.math.MathContext(SIGNIFICANT, java.math.RoundingMode.HALF_UP),
        ).toDouble()

        val magnitude = abs(rounded)
        if (magnitude >= 1e12 || magnitude < 1e-9) {
            return String.format(Locale.getDefault(), "%.6E", rounded)
        }
        val plain = if (rounded == rounded.roundToLong().toDouble()) {
            rounded.roundToLong().toString()
        } else {
            rounded.toBigDecimal().stripTrailingZeros().toPlainString()
        }
        return group(plain)
    }

    /**
     * The locale's decimal separator. The `.` key prints this; [parse] undoes it. Read per
     * use, not captured at class load, so a change of app language takes it along —
     * `app-languages` D8.
     */
    val DECIMAL: Char get() = DecimalFormatSymbols.getInstance().decimalSeparator

    private val GROUPING: Char get() = DecimalFormatSymbols.getInstance().groupingSeparator

    /**
     * Read a number the keypad typed.
     *
     * The entry buffer holds whatever [DECIMAL] is, because that is what the `.` key
     * printed into it; `toDouble` only knows `.`, so this is the one place the two
     * meet. Null rather than an exception: a half-typed number is an ordinary state,
     * not a failure.
     */
    fun parse(entry: String): Double? =
        entry.replace(GROUPING.toString(), "").replace(DECIMAL, '.').toDoubleOrNull()

    /**
     * Group the integer part of an already-plain number.
     *
     * `1234567` and not `1,234,567` is a small thing that reads as a debug build.
     * Locale separators: a number is formatted the way the app's language formats
     * numbers.
     */
    private fun group(plain: String): String {
        val negative = plain.startsWith("-")
        val body = if (negative) plain.substring(1) else plain
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val fraction = if (dot < 0) "" else body.substring(dot + 1)

        val grouped = StringBuilder()
        whole.forEachIndexed { i, c ->
            if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(GROUPING)
            grouped.append(c)
        }
        return buildString {
            if (negative) append('-')
            append(grouped)
            if (fraction.isNotEmpty()) append(DECIMAL).append(fraction)
        }
    }

    /**
     * What the screen says when an expression fails — P2-25.
     *
     * Four causes, four sentences. `Error` for all of them is what a debug build
     * prints; "Can't divide by zero" is what a calculator prints, and the difference
     * is the difference between a cover and a stand-in.
     */
    fun message(words: Words, failure: Throwable): String = words.get(
        when (failure) {
            is DividedByZero -> R.string.calc_divide_by_zero
            is Undefined -> R.string.calc_undefined
            is Overflow -> R.string.calc_infinity
            else -> R.string.calc_invalid_format
        }
    )

    /**
     * The expression as a calculator prints it — P2-25.
     *
     * **This is the fix for the loudest tell the cover had.** P2-24 showed the entry
     * buffer alone, so typing `1 + 2` displayed `2` and the expression being built was
     * invisible until equals. Nothing on either store behaves that way.
     *
     * **Only glyphs Inter certainly carries.** `×`, `÷`, `−`, `√`, `π` are drawn;
     * superscripts are not, and `x²` prints `^2` rather than `²` for the reason the
     * keypad labels use a baseline shift — a modifier letter the face lacks renders as
     * a tofu box, which is a worse tell than the one being fixed. RealCalc prints its
     * expressions this way for the same reason.
     */
    fun render(tokens: List<Token>, entry: String = ""): String {
        val out = StringBuilder()
        tokens.forEach { token ->
            when (token) {
                is Token.Num -> out.append(runCatching { format(token.value) }.getOrDefault(""))
                is Token.Const -> out.append(if (token.name == "pi") "π" else "e")
                is Token.Op -> out.append(
                    when (token.symbol) {
                        '+' -> " + "
                        '-' -> " − "
                        '*' -> " × "
                        '/' -> " ÷ "
                        else -> "^"
                    }
                )
                is Token.Fn -> out.append(
                    when (token.name) {
                        "sqrt" -> "√"
                        "exp" -> "e^"
                        "pow10" -> "10^"
                        else -> token.name
                    }
                )
                is Token.Post -> out.append(
                    when (token.name) {
                        "sqr" -> "^2"
                        "inv" -> "^-1"
                        "fact" -> "!"
                        else -> "%"
                    }
                )
                Token.Open -> out.append("(")
                Token.Close -> out.append(")")
            }
        }
        out.append(entry)
        return out.toString().trim()
    }

    /**
     * The running value of an expression that is not finished — P2-25.
     *
     * Open parentheses are closed and a trailing operator is dropped, which is how
     * Google Calculator arrives at the number it shows under what you are typing.
     *
     * **Never throws, and returns null rather than a message.** A half-typed
     * expression is not an error and must not read as one: `1 ÷` on its way to `1 ÷ 2`
     * would otherwise flash "Can't divide by zero" between two keystrokes.
     */
    fun preview(tokens: List<Token>, entry: String = "", angle: Angle = Angle.Radians): Double? {
        var stream = tokens + (parse(entry)?.let { listOf(Token.Num(it)) } ?: emptyList())
        while (stream.isNotEmpty() && stream.last() is Token.Op) stream = stream.dropLast(1)
        val unclosed = stream.count { it is Token.Open } - stream.count { it is Token.Close }
        repeat(maxOf(unclosed, 0)) { stream = stream + Token.Close }
        return try {
            evaluate(stream, angle)
        } catch (_: Exception) {
            null
        }
    }

    /** π and e, so the keypad has one place to read them from. */
    const val PI_VALUE = PI
    const val E_VALUE = kotlin.math.E
}
