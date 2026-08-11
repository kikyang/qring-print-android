package com.qring.print

/**
 * 形态学操作（移植自 xyprt/Morphology，2026-08-11，反编译完整逐行翻译）。
 * 用于描边结果后处理：prune 剪掉孤点/毛刺，despeckle 删掉小连通块。
 */
object Morphology {

    const val SMOOTH_MIN_PX = 5
    const val PRUNE_ROUNDS = 2

    /** 平滑 = 2 轮 prune + 连通块 <5px 的 despeckle */
    fun smooth(mask: BooleanArray, width: Int, height: Int): BooleanArray =
        despeckle(prune(mask, width, height, PRUNE_ROUNDS), width, height, SMOOTH_MIN_PX)

    /** 每轮基于上一轮快照：黑像素的 8 邻域黑邻居数 ≤1 则删（孤点/端毛刺） */
    fun prune(mask: BooleanArray, width: Int, height: Int, rounds: Int): BooleanArray {
        var current = mask
        repeat(rounds) {
            val copy = current.copyOf()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    if (!current[i]) continue
                    var neighbors = 0
                    for (oy in -1..1) {
                        for (ox in -1..1) {
                            if (ox == 0 && oy == 0) continue
                            val nx = x + ox
                            val ny = y + oy
                            if (nx in 0 until width && ny in 0 until height && current[ny * width + nx]) neighbors++
                        }
                    }
                    if (neighbors <= 1) copy[i] = false
                }
            }
            current = copy
        }
        return current
    }

    /** 8 邻域连通域标记（栈 DFS），连通块尺寸 < minSize 的删除 */
    fun despeckle(mask: BooleanArray, width: Int, height: Int, minSize: Int): BooleanArray {
        val n = mask.size
        val out = BooleanArray(n)
        val visited = BooleanArray(n)
        val stack = ArrayDeque<Int>()
        val component = ArrayList<Int>()
        for (start in 0 until n) {
            if (!mask[start] || visited[start]) continue
            component.clear()
            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                component.add(i)
                val cx = i % width
                val cy = i / width
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        if (ox == 0 && oy == 0) continue
                        val nx = cx + ox
                        val ny = cy + oy
                        if (nx in 0 until width && ny in 0 until height) {
                            val j = ny * width + nx
                            if (mask[j] && !visited[j]) {
                                visited[j] = true
                                stack.addLast(j)
                            }
                        }
                    }
                }
            }
            if (component.size >= minSize) {
                for (p in component) out[p] = true
            }
        }
        return out
    }
}
