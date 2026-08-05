package com.dkrepo

/**
 * Minimal WebAssembly (MVP) interpreter, purpose-built for the flixcloud.cc
 * w_payload module which exports memory + _s(seed) + _r(a,b,c,out,len) + _c().
 *
 * The payload constants and operations rotate on every deploy, so instead of
 * hardcoding the byte transform we execute the actual WASM. Only the small
 * opcode subset used by these modules is implemented (i32 ops, load8/store8,
 * block/loop/br, globals, one memory, active data segments).
 */
class WasmInterpreter(wasm: ByteArray) {

    private class Func(
        val localTypes: MutableList<Int>,
        val exprStart: Int,
        val exprEnd: Int,
        // blockStartPos -> Pair(endPos, isLoop)
        val blockOpens: Map<Int, Pair<Int, Boolean>>
    )

    private val bytes = wasm
    private val funcs = ArrayList<Func>()
    private val exports = HashMap<String, Int>() // name -> func index
    private val globals = ArrayList<Int>()
    private var memPages = 1
    private val dataSegments = ArrayList<Pair<Int, ByteArray>>()

    lateinit var memory: ByteArray
        private set

    init {
        parse()
        memory = ByteArray(maxOf(memPages, 1) * 65536)
        for ((offset, data) in dataSegments) {
            if (offset + data.size <= memory.size) {
                System.arraycopy(data, 0, memory, offset, data.size)
            }
        }
    }

    private fun readSLeb(posIn: Int): Pair<Int, Int> {
        var pos = posIn
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = bytes[pos].toInt() and 0xFF; pos++
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        if (shift < 32 && b and 0x40 != 0) result = result or (-1 shl shift)
        return Pair(result, pos)
    }

    private fun readULeb(posIn: Int): Pair<Int, Int> {
        var pos = posIn
        var result = 0L
        var shift = 0
        var b: Int
        do {
            b = bytes[pos].toInt() and 0xFF; pos++
            result = result or ((b and 0x7F).toLong() shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        return Pair((result and 0xFFFFFFFFL).toInt(), pos)
    }
    private fun parse() {
        var pos = 8 // skip magic + version
        while (pos < bytes.size) {
            val secId = bytes[pos].toInt() and 0xFF; pos++
            val (secSize, secStart) = readULeb(pos)
            pos = secStart
            val secEnd = pos + secSize
            when (secId) {
                5 -> { // memory section
                    var p = pos
                    val (count, p1) = readULeb(p); p = p1
                    repeat(count) {
                        val flags = bytes[p].toInt() and 0xFF; p++
                        val (min, p2) = readULeb(p); p = p2
                        if (flags and 1 != 0) { val (_, p3) = readULeb(p); p = p3 }
                        memPages = min
                    }
                }
                6 -> { // global section
                    var p = pos
                    val (count, p1) = readULeb(p); p = p1
                    repeat(count) {
                        p++ // valtype
                        p++ // mutability
                        if (bytes[p].toInt() and 0xFF != 0x41) throw IllegalStateException("unsupported global init")
                        val (v, p2) = readSLeb(p + 1); p = p2
                        if (bytes[p].toInt() and 0xFF != 0x0B) throw IllegalStateException("bad global init")
                        p++
                        globals.add(v)
                    }
                }
                7 -> { // export section
                    var p = pos
                    val (count, p1) = readULeb(p); p = p1
                    repeat(count) {
                        val (nameLen, p2) = readULeb(p); p = p2
                        val name = String(bytes, p, nameLen, Charsets.UTF_8); p += nameLen
                        val kind = bytes[p].toInt() and 0xFF; p++
                        val (idx, p3) = readULeb(p); p = p3
                        if (kind == 0) exports[name] = idx
                    }
                }
                10 -> { // code section
                    var p = pos
                    val (count, p1) = readULeb(p); p = p1
                    repeat(count) {
                        val (bodySize, p2) = readULeb(p); p = p2
                        val bodyEnd = p2 + bodySize
                        var q = p2
                        val (localGroups, q1) = readULeb(q); q = q1
                        val localTypes = ArrayList<Int>()
                        repeat(localGroups) {
                            val (c, q2) = readULeb(q); q = q2
                            val t = bytes[q].toInt() and 0xFF; q++
                            repeat(c) { localTypes.add(t) }
                        }
                        funcs.add(Func(localTypes, q, bodyEnd, buildBlockMap(q, bodyEnd)))
                        p = bodyEnd
                    }
                }
                11 -> { // data section
                    var p = pos
                    val (count, p1) = readULeb(p); p = p1
                    repeat(count) {
                        val (flags, p2) = readULeb(p); p = p2
                        if (flags != 0) throw IllegalStateException("unsupported data segment flags $flags")
                        if (bytes[p].toInt() and 0xFF != 0x41) throw IllegalStateException("unsupported data offset expr")
                        val (off, p3) = readSLeb(p + 1); p = p3
                        if (bytes[p].toInt() and 0xFF != 0x0B) throw IllegalStateException("bad data expr")
                        p++
                        val (sz, p4) = readULeb(p); p = p4
                        dataSegments.add(Pair(off, bytes.copyOfRange(p, p + sz)))
                        p += sz
                    }
                }
            }
            pos = secEnd
        }
    }

    /** Matches block/loop opcodes with their `end` so branches can jump. */
    private fun buildBlockMap(start: Int, end: Int): Map<Int, Pair<Int, Boolean>> {
        val opens = HashMap<Int, Pair<Int, Boolean>>()
        val stack = ArrayList<Pair<Int, Boolean>>()
        var p = start
        while (p < end) {
            when (val op = bytes[p].toInt() and 0xFF) {
                0x02, 0x03 -> { stack.add(Pair(p, op == 0x03)); p += 2 }
                0x04 -> { stack.add(Pair(p, false)); p += 2 }
                0x0B -> {
                    if (stack.isNotEmpty()) {
                        val (openPos, isLoop) = stack.removeAt(stack.size - 1)
                        opens[openPos] = Pair(p, isLoop)
                    }
                    if (stack.isEmpty()) return opens
                    p++
                }
                0x0C, 0x0D, 0x20, 0x21, 0x22, 0x23, 0x24 -> p += 2
                0x41 -> { val (_, np) = readSLeb(p + 1); p = np }
                in 0x28..0x3E -> { val (_, p1) = readULeb(p + 1); val (_, p2) = readULeb(p1); p = p2 }
                else -> p++
            }
        }
        return opens
    }
    /** Calls an exported function by name. Returns the top-of-stack value (or 0). */
    fun call(name: String, args: IntArray): Int {
        val funcIdx = exports[name] ?: throw IllegalStateException("no export func $name")
        val f = funcs[funcIdx]
        val locals = IntArray(f.localTypes.size + args.size)
        for (i in args.indices) locals[i] = args[i]
        val stack = ArrayList<Int>(64)
        val labels = ArrayList<Pair<Int, Boolean>>() // pos of block/loop instr, isLoop
        var pc = f.exprStart
        var steps = 0

        fun pop(): Int = stack.removeAt(stack.size - 1)
        fun push(v: Int) { stack.add(v) }
        fun binOp(): Pair<Int, Int> { val y = pop(); val x = pop(); return Pair(x, y) }

        while (pc < f.exprEnd) {
            if (++steps > 20_000_000) throw IllegalStateException("wasm step limit exceeded")
            when (bytes[pc].toInt() and 0xFF) {
                0x0B -> { if (labels.isNotEmpty()) labels.removeAt(labels.size - 1); pc++ }
                0x02 -> { labels.add(Pair(pc, false)); pc += 2 }
                0x03 -> { labels.add(Pair(pc, true)); pc += 2 }
                0x0C, 0x0D -> { // br / br_if
                    val depth = bytes[pc + 1].toInt() and 0xFF
                    val take = if (bytes[pc].toInt() and 0xFF == 0x0D) pop() != 0 else true
                    if (take) {
                        val target = labels[labels.size - 1 - depth]
                        val info = f.blockOpens[target.first]
                            ?: throw IllegalStateException("no block info for ${target.first}")
                        if (info.second) { // loop: label stays, jump to start
                            val newSize = labels.size - depth
                            while (labels.size > newSize) labels.removeAt(labels.size - 1)
                            pc = target.first + 2
                        } else { // block: pop label, jump past end
                            val newSize = labels.size - 1 - depth
                            while (labels.size > newSize) labels.removeAt(labels.size - 1)
                            pc = info.first + 1
                        }
                    } else pc += 2
                }
                0x20 -> { push(locals[bytes[pc + 1].toInt() and 0xFF]); pc += 2 }
                0x21 -> { locals[bytes[pc + 1].toInt() and 0xFF] = pop(); pc += 2 }
                0x22 -> { locals[bytes[pc + 1].toInt() and 0xFF] = stack[stack.size - 1]; pc += 2 }
                0x23 -> { push(globals[bytes[pc + 1].toInt() and 0xFF]); pc += 2 }
                0x24 -> { globals[bytes[pc + 1].toInt() and 0xFF] = pop(); pc += 2 }
                0x41 -> { val (v, np) = readSLeb(pc + 1); push(v); pc = np }
                0x1A -> { pop(); pc++ }
                0x45 -> { push(if (pop() == 0) 1 else 0); pc++ }
                0x46 -> { val (x, y) = binOp(); push(if (x == y) 1 else 0); pc++ }
                0x47 -> { val (x, y) = binOp(); push(if (x != y) 1 else 0); pc++ }
                0x48 -> { val (x, y) = binOp(); push(if (x < y) 1 else 0); pc++ }
                0x49 -> { val (x, y) = binOp(); push(if (Integer.compareUnsigned(x, y) < 0) 1 else 0); pc++ }
                0x4A -> { val (x, y) = binOp(); push(if (x > y) 1 else 0); pc++ }
                0x4B -> { val (x, y) = binOp(); push(if (Integer.compareUnsigned(x, y) > 0) 1 else 0); pc++ }
                0x4C -> { val (x, y) = binOp(); push(if (x <= y) 1 else 0); pc++ }
                0x4D -> { val (x, y) = binOp(); push(if (Integer.compareUnsigned(x, y) <= 0) 1 else 0); pc++ }
                0x4E -> { val (x, y) = binOp(); push(if (x >= y) 1 else 0); pc++ }
                0x4F -> { val (x, y) = binOp(); push(if (Integer.compareUnsigned(x, y) >= 0) 1 else 0); pc++ }
                0x6A -> { val (x, y) = binOp(); push(x + y); pc++ }
                0x6B -> { val (x, y) = binOp(); push(x - y); pc++ }
                0x6C -> { val (x, y) = binOp(); push(x * y); pc++ }
                0x6D -> { val (x, y) = binOp(); push(if (y == 0) 0 else x / y); pc++ }
                0x6E -> { val (x, y) = binOp(); push(if (y == 0) 0 else Integer.divideUnsigned(x, y)); pc++ }
                0x6F -> { val (x, y) = binOp(); push(if (y == 0) 0 else x % y); pc++ }
                0x70 -> { val (x, y) = binOp(); push(if (y == 0) 0 else Integer.remainderUnsigned(x, y)); pc++ }
                0x71 -> { val (x, y) = binOp(); push(x and y); pc++ }
                0x72 -> { val (x, y) = binOp(); push(x or y); pc++ }
                0x73 -> { val (x, y) = binOp(); push(x xor y); pc++ }
                0x74 -> { val (x, y) = binOp(); push(x shl (y and 31)); pc++ }
                0x75 -> { val (x, y) = binOp(); push(x shr (y and 31)); pc++ }
                0x76 -> { val (x, y) = binOp(); push(x ushr (y and 31)); pc++ }
                0x77 -> { val (x, y) = binOp(); push(Integer.rotateLeft(x, y and 31)); pc++ }
                0x78 -> { val (x, y) = binOp(); push(Integer.rotateRight(x, y and 31)); pc++ }
                0x2C, 0x2D -> { // i32.load8_s / load8_u
                    val signed = (bytes[pc].toInt() and 0xFF) == 0x2C
                    val (_, p1) = readULeb(pc + 1)
                    val (offset, p2) = readULeb(p1); pc = p2
                    val addr = pop() + offset
                    val v = memory[addr].toInt()
                    push(if (signed) v else v and 0xFF)
                }
                0x3A -> { // i32.store8
                    val (_, p1) = readULeb(pc + 1)
                    val (offset, p2) = readULeb(p1); pc = p2
                    val v = pop()
                    val addr = pop() + offset
                    memory[addr] = (v and 0xFF).toByte()
                }
                else -> throw IllegalStateException(
                    "unsupported opcode 0x${(bytes[pc].toInt() and 0xFF).toString(16)} at $pc"
                )
            }
        }
        return if (stack.isNotEmpty()) pop() else 0
    }
}