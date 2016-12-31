package com.soywiz.korio.vfs

import com.soywiz.korio.async.*
import org.junit.Assert
import org.junit.Test

class ReadSpecialTest {
	class CharArray2(val width: Int, val height: Int, val data: CharArray) {
		fun index(x: Int, y: Int): Int = y * width + x
		operator fun get(x: Int, y: Int): Char = data[index(x, y)]
		operator fun set(x: Int, y: Int, v: Char): Unit = run { data[index(x, y)] = v }
	}

	fun VfsFile.decorateWithCharArrayReading(): VfsFile = VfsFile(object : Vfs.Decorator(this) {
		suspend override fun <T : Any> readSpecial(path: String, clazz: Class<T>, onProgress: (Long, Long) -> Unit): T = asyncFun {
			when (clazz) {
				CharArray2::class.java -> {
					val text = parentVfs.readFully(path).toString(Charsets.UTF_8)
					val side = Math.sqrt(text.length.toDouble()).toInt()
					CharArray2(side, side, text.toCharArray()) as T
				}
				else -> super.readSpecial(path, clazz, onProgress) as T
			}
		}
	}, this.path)

	suspend fun VfsFile.readCharArray2() = readSpecial<CharArray2>()

	@Test
	fun testReadSpecial() = sync {
		val temp = MemoryVfs().decorateWithCharArrayReading()
		val f2 = temp["korio.chararray2"]
		f2.writeString("123456789")
		val c2 = f2.readCharArray2()
		Assert.assertEquals('1', c2[0, 0])
		Assert.assertEquals('5', c2[1, 1])
		Assert.assertEquals('9', c2[2, 2])
	}

	@Test
	fun testReadSpecial2() = sync {
		val temp = MemoryVfs()
		temp.vfs.registerReadSpecial { file, onProgress -> async {
			val text = file.readString()
			val side = Math.sqrt(text.length.toDouble()).toInt()
			CharArray2(side, side, text.toCharArray())
		} }
		val f2 = temp["korio.chararray2"]
		f2.writeString("123456789")
		val c2 = f2.readCharArray2()
		Assert.assertEquals('1', c2[0, 0])
		Assert.assertEquals('5', c2[1, 1])
		Assert.assertEquals('9', c2[2, 2])
	}
}