package wayzer.competition

import arc.graphics.Colors
import java.util.Stack

fun colorClearedName(name : String): String {
    val stack = Stack<Int>()
    var count = 0
    var result = name
    val removed = mutableListOf<IntRange>()
    name.forEachIndexed { index, c ->
        when (c) {
            '[' -> {
                if (!stack.empty() && stack.peek() == index-1) stack.pop()
                else stack.push(index)
            }
            ']' -> if (!stack.empty()) {
                val last = stack.peek()
                if (last+1 == index) {
                    if (count == 0) removed.add(IntRange(last, index))
                    else count--
                } else if (Colors.get(name.substring(last+1, index)) != null) {
                    count++
                }
                stack.pop()
            }
        }
    }
    removed.addAll(stack.map { IntRange(it, it) } )
    removed.sortBy { it.first }
    removed.reverse()
    for (range in removed) {
        result = result.removeRange(range)
    }
    repeat(count) { result += "[]" }
    return result
}
