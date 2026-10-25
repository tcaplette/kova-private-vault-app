package com.app.vault

import com.app.vault.Calculator.Angle
import com.app.vault.Calculator.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The calculator cover's evaluator — P2-24.
 *
 * A cover only works while it is a *convincing* instance of the thing it claims to
 * be, so wrong arithmetic is a security defect here and not merely a bug: a
 * calculator that gets `2 + 3 * 4` wrong is one somebody looks at twice.
 *
 * JVM scope — it is arithmetic.
 */
class CalculatorTest {

    private fun n(v: Double) = Token.Num(v)
    private fun op(c: Char) = Token.Op(c)
    private fun fn(s: String) = Token.Fn(s)
    private val open = Token.Open
    private val close = Token.Close

    private fun eval(vararg tokens: Token, angle: Angle = Angle.Radians) =
        Calculator.evaluate(tokens.toList(), angle)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue("expected $expected, was $actual", abs(expected - actual) < tolerance)
    }

    // --- precedence ----------------------------------------------------------

    @Test
    fun multiplicationBindsTighterThanAddition() {
        assertClose(14.0, eval(n(2.0), op('+'), n(3.0), op('*'), n(4.0)))
        assertClose(20.0, eval(n(2.0), op('+'), n(3.0), op('*'), n(4.0), op('+'), n(6.0)))
    }

    @Test
    fun subtractionIsLeftAssociative() {
        // 10 - 3 - 2 is 5, not 9. A right-associative slip prints 9.
        assertClose(5.0, eval(n(10.0), op('-'), n(3.0), op('-'), n(2.0)))
    }

    @Test
    fun divisionIsLeftAssociative() {
        assertClose(2.0, eval(n(16.0), op('/'), n(4.0), op('/'), n(2.0)))
    }

    /** `^` is the one right-associative operator: 2^3^2 is 2^9 = 512, not 64. */
    @Test
    fun exponentiationIsRightAssociative() {
        assertClose(512.0, eval(n(2.0), op('^'), n(3.0), op('^'), n(2.0)))
    }

    @Test
    fun parenthesesOverridePrecedence() {
        assertClose(20.0, eval(open, n(2.0), op('+'), n(3.0), close, op('*'), n(4.0)))
    }

    @Test
    fun parenthesesNest() {
        // ((2 + 3) * (4 - 1)) ^ 2 = 225
        assertClose(
            225.0,
            eval(
                open, open, n(2.0), op('+'), n(3.0), close, op('*'),
                open, n(4.0), op('-'), n(1.0), close, close, op('^'), n(2.0),
            ),
        )
    }

    // --- unary minus ---------------------------------------------------------

    @Test
    fun unaryMinusNegatesAValue() {
        assertClose(-5.0, eval(op('-'), n(5.0)))
        assertClose(-1.0, eval(n(4.0), op('+'), op('-'), n(5.0)))
    }

    /** `-2^2` is `-(2^2)` = -4, which is what a scientific calculator prints. */
    @Test
    fun unaryMinusBindsAsAPrefixOnTheOperand() {
        assertClose(-4.0, eval(op('-'), n(2.0), op('^'), n(2.0)))
    }

    /**
     * A minus is allowed *after* the caret even though one before the base binds
     * looser than the caret. `2^-3` is `0.125`; the two rules meet here and the
     * meeting is the part worth pinning down.
     */
    @Test
    fun aNegativeExponentParses() {
        assertClose(0.125, eval(n(2.0), op('^'), op('-'), n(3.0)))
    }

    @Test
    fun doubleNegationIsAllowed() {
        assertClose(5.0, eval(op('-'), op('-'), n(5.0)))
    }

    // --- functions and angle mode --------------------------------------------

    @Test
    fun trigonometryReadsTheAngleMode() {
        assertClose(1.0, eval(fn("sin"), open, n(90.0), close, angle = Angle.Degrees))
        assertClose(0.0, eval(fn("sin"), open, n(0.0), close, angle = Angle.Radians))
        assertClose(
            1.0,
            eval(fn("sin"), open, n(Calculator.PI_VALUE / 2), close, angle = Angle.Radians),
        )
    }

    /** The same expression must differ between modes, or the selector does nothing. */
    @Test
    fun theAngleModeChangesTheAnswer() {
        val degrees = eval(fn("cos"), open, n(1.0), close, angle = Angle.Degrees)
        val radians = eval(fn("cos"), open, n(1.0), close, angle = Angle.Radians)
        assertTrue("the angle mode had no effect", abs(degrees - radians) > 0.4)
    }

    @Test
    fun logarithmsAndRoots() {
        assertClose(1.0, eval(fn("ln"), open, n(Calculator.E_VALUE), close))
        assertClose(3.0, eval(fn("log"), open, n(1000.0), close))
        assertClose(4.0, eval(fn("sqrt"), open, n(16.0), close))
    }

    /** A trig call inside a nested expression — P2-24's Verify clause, literally. */
    @Test
    fun aNestedExpressionWithATrigCall() {
        // (2 + sin(90)) * 3 = 9, in degrees
        assertClose(
            9.0,
            eval(
                open, n(2.0), op('+'), fn("sin"), open, n(90.0), close, close,
                op('*'), n(3.0),
                angle = Angle.Degrees,
            ),
        )
    }

    // --- errors --------------------------------------------------------------

    @Test
    fun divisionByZeroIsReported() {
        assertThrows(Calculator.DividedByZero::class.java) {
            eval(n(1.0), op('/'), n(0.0))
        }
    }

    @Test
    fun malformedInputIsRejectedRatherThanGuessed() {
        assertThrows(Calculator.Malformed::class.java) { Calculator.evaluate(emptyList()) }
        assertThrows(Calculator.Malformed::class.java) { eval(n(1.0), op('+')) }
        assertThrows(Calculator.Malformed::class.java) { eval(open, n(1.0)) }
        assertThrows(Calculator.Malformed::class.java) { eval(n(1.0), n(2.0)) }
        assertThrows(Calculator.Malformed::class.java) { eval(fn("sin"), n(1.0)) }
    }

    // --- the display ---------------------------------------------------------

    /**
     * **The clause P2-24 names.** `Double` cannot hold `0.1 + 0.2`, so an unrounded
     * display reads `0.30000000000000004` — the single most likely thing to make
     * somebody decide this is not the calculator it claims to be.
     */
    @Test
    fun zeroPointOnePlusZeroPointTwoDisplaysAsZeroPointThree() {
        assertEquals("0.3", Calculator.format(eval(n(0.1), op('+'), n(0.2))))
    }

    @Test
    fun wholeNumbersLoseTheirDecimalPoint() {
        assertEquals("4", Calculator.format(4.0))
        assertEquals("-4", Calculator.format(-4.0))
        assertEquals("0", Calculator.format(0.0))
    }

    @Test
    fun trailingZerosAreDropped() {
        assertEquals("1.5", Calculator.format(1.5000000))
        assertEquals("0.25", Calculator.format(0.25))
    }

    @Test
    fun aThirdRoundsRatherThanRunningOn() {
        val third = Calculator.format(eval(n(1.0), op('/'), n(3.0)))
        assertTrue("a third printed as $third", third.length <= 14)
        assertTrue("a third printed as $third", third.startsWith("0.333333333333"))
    }

    @Test
    fun extremeMagnitudesFallBackToExponentForm() {
        assertTrue(Calculator.format(1.0e20).contains("E"))
        assertTrue(Calculator.format(1.0e-15).contains("E"))
    }

    /**
     * **P2-25 changed this test's expectation on purpose.** It used to assert all
     * three read `"Error"`. A single word covering a NaN and an overflow is what a
     * debug build prints; the causes are named now, and [Calculator.message] turns
     * each into its own sentence.
     */
    @Test
    fun nonFiniteResultsAreNamedCausesRatherThanTheWordError() {
        assertThrows(Calculator.Undefined::class.java) { Calculator.format(Double.NaN) }
        assertThrows(Calculator.Overflow::class.java) { Calculator.format(Double.POSITIVE_INFINITY) }
        assertThrows(Calculator.Overflow::class.java) { Calculator.format(Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun theFunctionSetIsExactlyWhatTheKeypadOffers() {
        listOf("sin", "cos", "tan", "ln", "log", "sqrt", "asin", "acos", "atan", "exp", "pow10")
            .forEach { assertTrue("$it is missing", Calculator.isFunction(it)) }
        listOf("sinh", "cbrt", "mem", "sqr", "fact").forEach {
            assertTrue("$it should not be a prefix function", !Calculator.isFunction(it))
        }
        listOf("sqr", "inv", "fact", "pct").forEach {
            assertTrue("$it is missing", Calculator.isPostfix(it))
        }
        listOf("sin", "mem", "sqrt").forEach {
            assertTrue("$it should not be postfix", !Calculator.isPostfix(it))
        }
    }

    // --- P2-25: the postfix operators ----------------------------------------

    private fun post(name: String) = Token.Post(name)
    private fun const(name: String) = Token.Const(name)

    @Test
    fun theSquareReciprocalFactorialAndPercentApply() {
        assertClose(9.0, eval(n(3.0), post("sqr")))
        assertClose(0.25, eval(n(4.0), post("inv")))
        assertClose(120.0, eval(n(5.0), post("fact")))
        assertClose(0.5, eval(n(50.0), post("pct")))
    }

    /**
     * Postfix binds tighter than everything, including the unary minus. `-3²` is
     * `-9` on every scientific calculator that ships, and a cover that prints `9` is
     * a cover disagreeing with the one already on the phone.
     */
    @Test
    fun postfixBindsTighterThanUnaryMinusAndThanTheCaret() {
        assertClose(-9.0, eval(op('-'), n(3.0), post("sqr")))
        assertClose(3.0, eval(n(1.0), op('+'), n(2.0), post("sqr"), op('/'), n(2.0)))
        // Stacked, innermost first: (3²)! = 9! = 362880
        assertClose(362880.0, eval(n(3.0), post("sqr"), post("fact")))
    }

    @Test
    fun factorialRefusesFractionsNegativesAndOverflow() {
        assertThrows(Calculator.Undefined::class.java) { eval(n(2.5), post("fact")) }
        assertThrows(Calculator.Undefined::class.java) { eval(n(-1.0), post("fact")) }
        assertThrows(Calculator.Overflow::class.java) { eval(n(171.0), post("fact")) }
        // 170! is the largest a Double holds, and it must still be reachable.
        assertTrue(eval(n(170.0), post("fact")).isFinite())
    }

    @Test
    fun theReciprocalOfZeroIsADivisionByZero() {
        assertThrows(Calculator.DividedByZero::class.java) { eval(n(0.0), post("inv")) }
    }

    // --- P2-25: the inverse functions and their domains ----------------------

    @Test
    fun theInverseTrigonometryReturnsAnAngleInTheActiveMode() {
        assertClose(30.0, eval(fn("asin"), open, n(0.5), close, angle = Angle.Degrees), 1e-9)
        assertClose(60.0, eval(fn("acos"), open, n(0.5), close, angle = Angle.Degrees), 1e-9)
        assertClose(45.0, eval(fn("atan"), open, n(1.0), close, angle = Angle.Degrees), 1e-9)
        assertClose(kotlin.math.PI / 6.0, eval(fn("asin"), open, n(0.5), close), 1e-12)
    }

    @Test
    fun theExponentialsAreTheInversesTheyClaimToBe() {
        assertClose(kotlin.math.E, eval(fn("exp"), open, n(1.0), close))
        assertClose(1000.0, eval(fn("pow10"), open, n(3.0), close))
    }

    /**
     * Every one of these returns a NaN from `kotlin.math` rather than throwing, so
     * without the domain guards they would reach the display as one silent word.
     */
    @Test
    fun domainErrorsAreUndefinedRatherThanSilentNaNs() {
        listOf(
            listOf(fn("ln"), open, n(-1.0), close),
            listOf(fn("ln"), open, n(0.0), close),
            listOf(fn("log"), open, n(0.0), close),
            listOf(fn("sqrt"), open, n(-1.0), close),
            listOf(fn("asin"), open, n(2.0), close),
            listOf(fn("acos"), open, n(-2.0), close),
        ).forEach { tokens ->
            assertThrows(Calculator.Undefined::class.java) { Calculator.evaluate(tokens) }
        }
    }

    // --- P2-25: the constants are symbols, not their digits ------------------

    @Test
    fun theConstantsEvaluateAndRenderAsThemselves() {
        assertClose(kotlin.math.PI, eval(const("pi")))
        assertClose(kotlin.math.E, eval(const("e")))
        assertEquals("π × e", Calculator.render(listOf(const("pi"), op('*'), const("e"))))
    }

    // --- P2-25: the display -------------------------------------------------

    private val symbols = java.text.DecimalFormatSymbols.getInstance()

    @Test
    fun theIntegerPartIsGrouped() {
        val g = symbols.groupingSeparator
        assertEquals("1${g}234${g}567", Calculator.format(1234567.0))
        assertEquals("-1${g}000", Calculator.format(-1000.0))
        assertEquals("999", Calculator.format(999.0))
    }

    @Test
    fun theFractionKeepsTheLocaleSeparatorAndIsNotGrouped() {
        val d = symbols.decimalSeparator
        assertEquals("1${symbols.groupingSeparator}000${d}5", Calculator.format(1000.5))
        assertEquals("0${d}3", Calculator.format(eval(n(0.1), op('+'), n(0.2))))
    }

    @Test
    fun parseUndoesWhateverTheDecimalKeyPrinted() {
        val d = symbols.decimalSeparator
        assertEquals(1.5, Calculator.parse("1${d}5"))
        assertEquals(null, Calculator.parse(""))
        assertEquals(null, Calculator.parse("-"))
    }

    /**
     * The tell P2-25 exists to remove: `1 + 2` used to display `2`.
     */
    @Test
    fun theExpressionIsRenderedAsACalculatorPrintsIt() {
        assertEquals("1 + 2", Calculator.render(listOf(n(1.0), op('+')), "2"))
        assertEquals(
            "sin(30) × 4 ÷ 2 +",
            Calculator.render(
                listOf(
                    fn("sin"), open, n(30.0), close,
                    op('*'), n(4.0), op('/'), n(2.0), op('+'),
                ),
            ),
        )
    }

    @Test
    fun noAsciiOperatorSurvivesRendering() {
        val rendered = Calculator.render(
            listOf(n(1.0), op('*'), n(2.0), op('/'), n(3.0), op('-'), fn("sqrt"), open, n(4.0), close),
        )
        listOf("*", "/", "sqrt").forEach {
            assertTrue("$it survived rendering: $rendered", !rendered.contains(it))
        }
        listOf("×", "÷", "√").forEach {
            assertTrue("$it is missing from $rendered", rendered.contains(it))
        }
    }

    @Test
    fun superscriptsAreRenderedWithoutModifierLetters() {
        val rendered = Calculator.render(listOf(n(3.0), post("sqr"), op('+'), n(4.0), post("inv")))
        assertEquals("3^2 + 4^-1", rendered)
        listOf("²", "ˣ", "ʸ", "⁻", "¹").forEach {
            assertTrue("a Unicode superscript reached the expression line: $rendered",
                !rendered.contains(it))
        }
    }

    // --- P2-25: the running result ------------------------------------------

    @Test
    fun aPartialExpressionPreviewsItsRunningValue() {
        assertClose(1.0, Calculator.preview(listOf(n(1.0), op('+')))!!)
        assertClose(0.5, Calculator.preview(listOf(fn("sin"), open), "30", Angle.Degrees)!!)
        assertClose(3.0, Calculator.preview(listOf(n(1.0), op('+')), "2")!!)
    }

    @Test
    fun anUnevaluablePartialExpressionPreviewsNothingRatherThanAnError() {
        assertEquals(null, Calculator.preview(listOf(open)))
        assertEquals(null, Calculator.preview(emptyList()))
        assertEquals(null, Calculator.preview(listOf(n(1.0), op('/')), "0"))
        assertEquals(null, Calculator.preview(listOf(fn("sin"), open, n(2.0), close), "3"))
    }

    // --- P2-25: what the screen says ----------------------------------------

    @Test
    fun everyCauseSaysSomethingDifferentAndNoneSaysError() {
        val said = listOf(
            Calculator.DividedByZero(),
            Calculator.Undefined(),
            Calculator.Overflow(),
            Calculator.Malformed("x"),
            IllegalStateException("something else"),
        ).map { Calculator.message(TestWords, it) }

        assertEquals("Can't divide by zero", said[0])
        assertEquals("Undefined", said[1])
        assertEquals("Infinity", said[2])
        assertEquals("Invalid format", said[3])
        assertEquals("Invalid format", said[4])
        assertEquals("two causes read the same", 4, said.toSet().size)
        said.forEach { assertEquals("a cause still reads Error", false, it == "Error") }
    }
}
