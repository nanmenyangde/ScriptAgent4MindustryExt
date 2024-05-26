package mapScript.tags

import mapScript.tags.Rated.TempData
import kotlin.math.pow

fun getEloWinProbability(ra : Double, rb : Double) : Double {
    return 1.0 / (1.0 + 10.0.pow((rb - ra) / 400))
}
fun getEloWinProbability(a : TempData, b : TempData) : Double {
    return getEloWinProbability(a.rating.toDouble(), b.rating.toDouble())
}

fun getSeed(groups : List<TempData>, rating : Int) : Double {
    var result = 1.0
    for (other in groups) {
        result += getEloWinProbability(other, TempData(setOf()).also { it.rating = rating })
    }
    return result
}

fun getRatingToRank(groups : List<TempData>, rank : Double) : Int {
    var left = 1; var right = 8000
    while (right - left > 1) {
        val mid = (left + right) / 2
        if (getSeed(groups, mid) < rank) right = mid
        else left = mid
    }
    return left
}