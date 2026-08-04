package uk.co.r99vitals

import kotlin.math.roundToInt

/**
 * Metric and imperial, with metric as the one that is actually stored.
 *
 * The ring is told height in centimetres and weight in kilograms whatever the wearer prefers to
 * read, so imperial is a display and entry convenience rather than a second source of truth.
 * Keeping one canonical unit means a preference change can never alter the numbers themselves.
 *
 * Round trips are stable to within a unit, which is the best that whole feet and inches allow:
 * 175 cm reads as 5'9" and 5'9" stores as 175 cm.
 */
object Units {

    private const val CM_PER_INCH = 2.54
    private const val LB_PER_KG = 2.2046226
    const val POUNDS_PER_STONE = 14

    fun kgToLb(kg: Int): Int = (kg * LB_PER_KG).roundToInt()

    fun lbToKg(lb: Int): Int = (lb / LB_PER_KG).roundToInt()

    /**
     * Whole stones and the pounds left over, which is how weight is spoken in Britain.
     *
     * The total is rounded to pounds before it is split, so a weight just under the next stone
     * reads as 12 st 0 lb rather than 11 st 14 lb.
     */
    fun kgToStones(kg: Int): Pair<Int, Int> {
        val pounds = kgToLb(kg)
        return pounds / POUNDS_PER_STONE to pounds % POUNDS_PER_STONE
    }

    fun stonesToKg(stones: Int, pounds: Int): Int = lbToKg(stones * POUNDS_PER_STONE + pounds)

    /**
     * Whole feet and inches. The total is rounded before it is split, so a height that lands
     * just under the next foot reads as 6'0" rather than 5'12".
     */
    fun cmToFeetInches(cm: Int): Pair<Int, Int> {
        val inches = (cm / CM_PER_INCH).roundToInt()
        return inches / 12 to inches % 12
    }

    fun feetInchesToCm(feet: Int, inches: Int): Int = ((feet * 12 + inches) * CM_PER_INCH).roundToInt()

    /** A walked distance, in whichever units are being read. */
    fun distance(metres: Int, metric: Boolean): String = when {
        metric && metres < 1000 -> "$metres m"
        metric -> "%.2f km".format(metres / 1000.0)
        // Under a couple of hundred yards a mile figure is all zeroes, so say yards instead.
        metres < 400 -> "%d yd".format((metres * 1.0936133).roundToInt())
        else -> "%.2f mi".format(metres / 1609.344)
    }

    fun weight(kg: Int, metric: Boolean, stones: Boolean = false): String = when {
        metric -> "$kg kg"
        stones -> kgToStones(kg).let { (st, lb) -> "$st st $lb lb" }
        else -> "${kgToLb(kg)} lb"
    }

    fun height(cm: Int, metric: Boolean): String =
        if (metric) "$cm cm" else cmToFeetInches(cm).let { (feet, inches) -> "$feet'$inches\"" }
}
