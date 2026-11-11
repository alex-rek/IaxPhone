package com.glaude.iaxphone.iax

/*
 * Кодек GSM 06.10 full rate (13 кбит/с): 160 отсчётов 16-битного PCM (20 мс) ↔ 33 байта.
 * Порт эталонной реализации libgsm (Jutta Degener, Carsten Bormann, TU Berlin) на Kotlin.
 * Арифметика повторяет 16/32-битную арифметику с насыщением из стандарта.
 */

private const val MIN_WORD = -32768
private const val MAX_WORD = 32767
private const val GSM_MAGIC = 0xD

private fun sat16(x: Int): Int = if (x < MIN_WORD) MIN_WORD else if (x > MAX_WORD) MAX_WORD else x
private fun sat32(x: Long): Long = x.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
/** Усечение до 16 бит, как при присваивании в word (short) в C. */
private fun w(x: Int): Int = x.toShort().toInt()
private fun add(a: Int, b: Int) = sat16(a + b)
private fun sub(a: Int, b: Int) = sat16(a - b)
private fun multR(a: Int, b: Int): Int = if (a == MIN_WORD && b == MIN_WORD) MAX_WORD else (a * b + 16384) shr 15
private fun mult(a: Int, b: Int): Int = if (a == MIN_WORD && b == MIN_WORD) MAX_WORD else (a * b) shr 15
private fun abs16(a: Int): Int = if (a < 0) (if (a == MIN_WORD) MAX_WORD else -a) else a

/** Число сдвигов влево, нормализующих 32-битное значение. */
private fun norm(a0: Int): Int {
    var a = a0
    if (a < 0) {
        if (a <= -1073741824) return 0
        a = a.inv()
    }
    return Integer.numberOfLeadingZeros(a) - 1
}

private fun div(num: Int, denum: Int): Int {
    if (num == 0) return 0
    var lNum = num
    var d = 0
    repeat(15) {
        d = d shl 1
        lNum = lNum shl 1
        if (lNum >= denum) { lNum -= denum; d++ }
    }
    return d
}

private fun asr(a: Int, n: Int): Int = when {
    n >= 16 -> if (a < 0) -1 else 0
    n <= -16 -> 0
    n < 0 -> w(a shl -n)
    else -> a shr n
}

private fun asl(a: Int, n: Int): Int = when {
    n >= 16 -> 0
    n <= -16 -> if (a < 0) -1 else 0
    n < 0 -> asr(a, -n)
    else -> w(a shl n)
}

private val DLB = intArrayOf(6554, 16384, 26214, 32767)
private val QLB = intArrayOf(3277, 11469, 21299, 32767)
private val NRFAC = intArrayOf(29128, 26215, 23832, 21846, 20165, 18725, 17476, 16384)
private val FAC = intArrayOf(18431, 20479, 22527, 24575, 26623, 28671, 30719, 32767)
private val LAR_BITS = intArrayOf(6, 6, 5, 5, 4, 4, 3, 3)

/** Параметры одного кадра. */
private class GsmFrame {
    val larc = IntArray(8)
    val nc = IntArray(4)
    val bc = IntArray(4)
    val mc = IntArray(4)
    val xmaxc = IntArray(4)
    val xmc = IntArray(52)

    fun pack(): ByteArray {
        val out = ByteArray(Gsm.FRAME_BYTES)
        var bitPos = 0
        fun put(value: Int, bits: Int) {
            for (b in bits - 1 downTo 0) {
                if ((value shr b) and 1 != 0) {
                    out[bitPos shr 3] = (out[bitPos shr 3].toInt() or (0x80 ushr (bitPos and 7))).toByte()
                }
                bitPos++
            }
        }
        put(GSM_MAGIC, 4)
        for (i in 0..7) put(larc[i], LAR_BITS[i])
        for (k in 0..3) {
            put(nc[k], 7); put(bc[k], 2); put(mc[k], 2); put(xmaxc[k], 6)
            for (i in 0..12) put(xmc[k * 13 + i], 3)
        }
        return out
    }

    companion object {
        fun unpack(data: ByteArray, offset: Int): GsmFrame {
            val f = GsmFrame()
            var bitPos = offset * 8
            fun get(bits: Int): Int {
                var v = 0
                repeat(bits) {
                    val bit = (data[bitPos shr 3].toInt() shr (7 - (bitPos and 7))) and 1
                    v = (v shl 1) or bit
                    bitPos++
                }
                return v
            }
            get(4) // сигнатура 0xD
            for (i in 0..7) f.larc[i] = get(LAR_BITS[i])
            for (k in 0..3) {
                f.nc[k] = get(7); f.bc[k] = get(2); f.mc[k] = get(2); f.xmaxc[k] = get(6)
                for (i in 0..12) f.xmc[k * 13 + i] = get(3)
            }
            return f
        }
    }
}

// ---------------------------------------------------------------------- общее для кодера и декодера

/** Декодирование LARc → LARpp (4.2.8). */
private fun decodeLar(larc: IntArray, larpp: IntArray) {
    fun step(i: Int, b: Int, mic: Int, inva: Int) {
        var t = w(add(larc[i], mic) shl 10)
        t = sub(t, b shl 1)
        t = multR(inva, t)
        larpp[i] = add(t, t)
    }
    step(0, 0, -32, 13107)
    step(1, 0, -32, 13107)
    step(2, 2048, -16, 13107)
    step(3, -2560, -16, 13107)
    step(4, 94, -8, 19223)
    step(5, -1792, -8, 17476)
    step(6, -341, -4, 31454)
    step(7, -1144, -4, 29708)
}

/** Интерполяция LAR для участка кадра (4.2.9) и перевод в коэффициенты отражения (4.2.10). */
private fun interpolatedRp(prev: IntArray, cur: IntArray, part: Int, out: IntArray) {
    for (i in 0..7) {
        out[i] = when (part) {
            0 -> add(add(prev[i] shr 2, cur[i] shr 2), prev[i] shr 1)
            1 -> add(prev[i] shr 1, cur[i] shr 1)
            2 -> add(add(prev[i] shr 2, cur[i] shr 2), cur[i] shr 1)
            else -> cur[i]
        }
    }
    for (i in 0..7) {
        val v = out[i]
        val t = if (v < 0) (if (v == MIN_WORD) MAX_WORD else -v) else v
        val r = if (t < 11059) t shl 1 else if (t < 20070) t + 11059 else add(t shr 2, 26112)
        out[i] = if (v < 0) -r else r
    }
}

private val SEGMENTS = intArrayOf(0, 13, 27, 40, 160)

private fun apcmXmaxcToExpMant(xmaxc: Int): Pair<Int, Int> {
    var exp = 0
    if (xmaxc > 15) exp = (xmaxc shr 3) - 1
    var mant = xmaxc - (exp shl 3)
    if (mant == 0) {
        exp = -4; mant = 7
    } else {
        while (mant <= 7) { mant = (mant shl 1) or 1; exp-- }
        mant -= 8
    }
    return exp to mant
}

private fun apcmInverseQuantization(xmc: IntArray, off: Int, mant: Int, exp: Int, xmp: IntArray) {
    val temp1 = FAC[mant]
    val temp2 = sub(6, exp)
    val temp3 = asl(1, sub(temp2, 1))
    for (i in 0..12) {
        var t = (xmc[off + i] shl 1) - 7
        t = w(t shl 12)
        t = multR(temp1, t)
        t = add(t, temp3)
        xmp[i] = asr(t, temp2)
    }
}

private fun rpeGridPositioning(mc: Int, xmp: IntArray, ep: IntArray, epOff: Int) {
    for (k in 0..39) ep[epOff + k] = 0
    for (i in 0..12) ep[epOff + mc + 3 * i] = xmp[i]
}

// ---------------------------------------------------------------------- кодер

class GsmEncoder {
    private val dp0 = IntArray(280)
    private var z1 = 0
    private var lz2 = 0L
    private var mp = 0
    private val u = IntArray(8)
    private val larpp = arrayOf(IntArray(8), IntArray(8))
    private var j = 0
    private val e = IntArray(50)

    /** Кодирует 160 отсчётов начиная с [off] в 33 байта. */
    fun encode(pcm: ShortArray, off: Int = 0): ByteArray {
        val f = GsmFrame()
        val so = IntArray(160)
        preprocess(pcm, off, so)
        lpcAnalysis(so, f.larc)
        shortTermAnalysis(f.larc, so)

        var dp = 120
        for (k in 0..3) {
            val d = k * 40
            val (nc, bc) = ltpParameters(so, d, dp)
            f.nc[k] = nc; f.bc[k] = bc
            // Долговременный анализ: dpp пишется на место dp[0..39], e — остаток.
            val bp = QLB[bc]
            val dpp = IntArray(40)
            for (i in 0..39) {
                dpp[i] = multR(bp, dp0[dp + i - nc])
                e[5 + i] = sub(so[d + i], dpp[i])
            }
            val (xmaxc, mc) = rpeEncoding(f.xmc, k * 13)
            f.xmaxc[k] = xmaxc; f.mc[k] = mc
            for (i in 0..39) dp0[dp + i] = add(e[5 + i], dpp[i])
            dp += 40
        }
        System.arraycopy(dp0, 160, dp0, 0, 120)
        return f.pack()
    }

    private fun preprocess(pcm: ShortArray, off: Int, so: IntArray) {
        for (k in 0..159) {
            val s0 = ((pcm[off + k].toInt() shr 3) shl 2)
            val s1 = s0 - z1
            z1 = s0
            var ls2 = s1.toLong() shl 15
            val msp = w((lz2 shr 15).toInt())
            val lsp = w((lz2 - (msp.toLong() shl 15)).toInt())
            ls2 += multR(lsp, 32735)
            val lTemp = msp.toLong() * 32735
            lz2 = sat32(lTemp + ls2)
            val lTemp2 = sat32(lz2 + 16384)
            val msp2 = multR(mp, -28180)
            mp = w((lTemp2 shr 15).toInt())
            so[k] = add(mp, msp2)
        }
    }

    private fun lpcAnalysis(s: IntArray, larc: IntArray) {
        val lacf = autocorrelation(s)
        reflectionCoefficients(lacf, larc)
        // Логарифмические отношения площадей (4.2.6)
        for (i in 0..7) {
            var t = abs16(larc[i])
            t = when {
                t < 22118 -> t shr 1
                t < 31130 -> t - 11059
                else -> (t - 26112) shl 2
            }
            larc[i] = if (larc[i] < 0) -t else t
        }
        // Квантование (4.2.7)
        val a = intArrayOf(20480, 20480, 20480, 20480, 13964, 15360, 8534, 9036)
        val b = intArrayOf(0, 0, 2048, -2560, 94, -1792, -341, -1144)
        val mac = intArrayOf(31, 31, 15, 15, 7, 7, 3, 3)
        val mic = intArrayOf(-32, -32, -16, -16, -8, -8, -4, -4)
        for (i in 0..7) {
            var t = mult(a[i], larc[i])
            t = add(t, b[i])
            t = add(t, 256)
            t = t shr 9
            larc[i] = if (t > mac[i]) mac[i] - mic[i] else if (t < mic[i]) 0 else t - mic[i]
        }
    }

    private fun autocorrelation(s: IntArray): LongArray {
        var smax = 0
        for (k in 0..159) { val t = abs16(s[k]); if (t > smax) smax = t }
        val scalauto = if (smax == 0) 0 else 4 - norm(smax shl 16)
        if (scalauto > 0) {
            val f = 16384 shr (scalauto - 1)
            for (k in 0..159) s[k] = multR(s[k], f)
        }
        val lacf = LongArray(9)
        for (k in 0..8) {
            var sum = 0L
            for (i in k..159) sum += s[i].toLong() * s[i - k]
            lacf[k] = sum shl 1
        }
        if (scalauto > 0) for (k in 0..159) s[k] = w(s[k] shl scalauto)
        return lacf
    }

    private fun reflectionCoefficients(lacf: LongArray, r: IntArray) {
        if (lacf[0] == 0L) { r.fill(0); return }
        val temp = norm(lacf[0].coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val acf = IntArray(9) { w(((lacf[it] shl temp) shr 16).toInt()) }
        val p = IntArray(9) { acf[it] }
        val kk = IntArray(9) { if (it in 1..7) acf[it] else 0 }
        for (n in 1..8) {
            val t = abs16(p[1])
            if (p[0] < t) {
                for (i in n..8) r[i - 1] = 0
                return
            }
            var rn = div(t, p[0])
            if (p[1] > 0) rn = -rn
            r[n - 1] = rn
            if (n == 8) return
            p[0] = add(p[0], multR(p[1], rn))
            for (m in 1..8 - n) {
                p[m] = add(p[m + 1], multR(kk[m], rn))
                kk[m] = add(kk[m], multR(p[m + 1], rn))
            }
        }
    }

    private fun shortTermAnalysis(larc: IntArray, s: IntArray) {
        val cur = larpp[j]
        j = j xor 1
        val prev = larpp[j]
        decodeLar(larc, cur)
        val rp = IntArray(8)
        for (part in 0..3) {
            interpolatedRp(prev, cur, part, rp)
            for (k in SEGMENTS[part] until SEGMENTS[part + 1]) {
                var di = s[k]
                var sav = di
                for (i in 0..7) {
                    val ui = u[i]
                    val rpi = rp[i]
                    u[i] = sav
                    sav = add(ui, multR(rpi, di))
                    di = add(di, multR(rpi, ui))
                }
                s[k] = di
            }
        }
    }

    /** Поиск задержки Nc и усиления bc долговременного предсказателя (4.2.11–4.2.12). */
    private fun ltpParameters(d: IntArray, dOff: Int, dp: Int): Pair<Int, Int> {
        var dmax = 0
        for (k in 0..39) { val t = abs16(d[dOff + k]); if (t > dmax) dmax = t }
        val temp = if (dmax == 0) 0 else norm(dmax shl 16)
        val scal = if (temp > 6) 0 else 6 - temp
        val wt = IntArray(40) { d[dOff + it] shr scal }

        var lMax = 0L
        var nc = 40
        for (lambda in 40..120) {
            var lr = 0L
            for (k in 0..39) lr += wt[k].toLong() * dp0[dp + k - lambda]
            if (lr > lMax) { nc = lambda; lMax = lr }
        }
        lMax = lMax shl 1
        lMax = lMax shr (6 - scal)

        var lPower = 0L
        for (k in 0..39) {
            val t = (dp0[dp + k - nc] shr 3).toLong()
            lPower += t * t
        }
        lPower = lPower shl 1

        if (lMax <= 0) return nc to 0
        if (lMax >= lPower) return nc to 3
        val lp = lPower.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sh = norm(lp)
        val r = w(((lMax shl sh) shr 16).toInt())
        val s = w(((lPower shl sh) shr 16).toInt())
        var bc = 0
        while (bc <= 2) {
            if (r <= mult(s, DLB[bc])) break
            bc++
        }
        return nc to bc
    }

    /** RPE-кодирование подкадра e[5..44]; возвращает (xmaxc, Mc), xMc пишется в [xmc]. */
    private fun rpeEncoding(xmc: IntArray, xmcOff: Int): Pair<Int, Int> {
        // Взвешивающий фильтр (4.2.13)
        val h = intArrayOf(-134, -374, 0, 2054, 5741, 8192, 5741, 2054, 0, -374, -134)
        val x = IntArray(40)
        for (k in 0..39) {
            var lr = 4096L
            for (i in 0..10) lr += e[k + i].toLong() * h[i]
            lr = lr shr 13
            x[k] = lr.coerceIn(MIN_WORD.toLong(), MAX_WORD.toLong()).toInt()
        }
        // Выбор сетки (4.2.14)
        var em = 0L
        var mc = 0
        for (m in 0..3) {
            var lr = 0L
            for (i in 0..12) { val t = (x[m + 3 * i] shr 2).toLong(); lr += t * t }
            lr = lr shl 1
            if (m == 0 || lr > em) { mc = m; em = lr }
        }
        val xM = IntArray(13) { x[mc + 3 * it] }

        // APCM-квантование (4.2.15)
        var xmax = 0
        for (i in 0..12) { val t = abs16(xM[i]); if (t > xmax) xmax = t }
        var exp = 0
        var temp = xmax shr 9
        var itest = false
        for (i in 0..5) {
            itest = itest || temp <= 0
            temp = temp shr 1
            if (!itest) exp++
        }
        val xmaxc = add(xmax shr (exp + 5), exp shl 3)
        val (e2, mant) = apcmXmaxcToExpMant(xmaxc)
        val temp1 = 6 - e2
        val temp2 = NRFAC[mant]
        for (i in 0..12) {
            var t = w(xM[i] shl temp1)
            t = mult(t, temp2)
            t = t shr 12
            xmc[xmcOff + i] = t + 4
        }
        // Обратное квантование и восстановление e[5..44] для обновления истории
        val xmp = IntArray(13)
        apcmInverseQuantization(xmc, xmcOff, mant, e2, xmp)
        rpeGridPositioning(mc, xmp, e, 5)
        return xmaxc to mc
    }
}

// ---------------------------------------------------------------------- декодер

class GsmDecoder {
    private val dp0 = IntArray(280)
    private val v = IntArray(9)
    private val larpp = arrayOf(IntArray(8), IntArray(8))
    private var j = 0
    private var nrp = 40
    private var msr = 0

    /** Декодирует один кадр (33 байта от [offset]) в 160 отсчётов. */
    fun decode(data: ByteArray, offset: Int = 0): ShortArray {
        val f = GsmFrame.unpack(data, offset)
        val wt = IntArray(160)
        val erp = IntArray(40)
        val xmp = IntArray(13)
        val drp = 120
        for (k in 0..3) {
            val (exp, mant) = apcmXmaxcToExpMant(f.xmaxc[k])
            apcmInverseQuantization(f.xmc, k * 13, mant, exp, xmp)
            rpeGridPositioning(f.mc[k], xmp, erp, 0)

            // Долговременный синтез (4.3.2)
            val nr = if (f.nc[k] < 40 || f.nc[k] > 120) nrp else f.nc[k]
            nrp = nr
            val brp = QLB[f.bc[k]]
            for (i in 0..39) {
                val drpp = multR(brp, dp0[drp + i - nr])
                dp0[drp + i] = add(erp[i], drpp)
            }
            for (i in 0..119) dp0[drp - 120 + i] = dp0[drp - 80 + i]
            for (i in 0..39) wt[k * 40 + i] = dp0[drp + i]
        }

        // Кратковременный синтез (4.3.3–4.3.4)
        val cur = larpp[j]
        j = j xor 1
        val prev = larpp[j]
        decodeLar(f.larc, cur)
        val rrp = IntArray(8)
        val s = IntArray(160)
        for (part in 0..3) {
            interpolatedRp(prev, cur, part, rrp)
            for (k in SEGMENTS[part] until SEGMENTS[part + 1]) {
                var sri = wt[k]
                for (i in 7 downTo 0) {
                    sri = sub(sri, w(multR(rrp[i], v[i])))
                    v[i + 1] = add(v[i], w(multR(rrp[i], sri)))
                }
                v[0] = sri
                s[k] = sri
            }
        }

        // Постобработка: деэмфазис, масштабирование (4.3.5–4.3.7)
        val out = ShortArray(160)
        for (k in 0..159) {
            msr = add(s[k], multR(msr, 28180))
            out[k] = (add(msr, msr) and 0xFFF8).toShort()
        }
        return out
    }
}

object Gsm {
    const val FRAME_SAMPLES = 160
    const val FRAME_BYTES = 33
}
