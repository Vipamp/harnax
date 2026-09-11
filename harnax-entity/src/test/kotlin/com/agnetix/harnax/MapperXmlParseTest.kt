package com.agnetix.harnax

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertTrue

/**
 * Mapper XML 可解析性测试
 *
 * `mybatis.mapper-locations` 指向 mapper 目录下的全部 XML，一个文件解析失败会让整个
 * SqlSessionFactory 建不起来，连带所有 Mapper 一起挂掉：而这属于「应用起不来」，不是「某个功能坏了」。
 * Testcontainers 的那批 Mapper 测试在没有 Docker 的环境里会直接跳过或报环境错，兜不住这类问题，
 * 所以在内存里过一遍 XML 解析，不依赖任何外部服务。
 *
 * 曾经真实踩过的一次：注释里写了「A -- B」，XML 注释体不允许出现连续的连字符。
 */
@DisplayName("Mapper XML 解析测试")
class MapperXmlParseTest {

    private fun mapperResources(): List<Resource> = PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml").toList()

    private fun newBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isValidating = false
        // 不联网取 mybatis-3-mapper.dtd，只判断是否 well-formed
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

    @Test
    @DisplayName("每个 mapper XML 都能被解析")
    fun `every mapper xml should be well-formed`() {
        val mappers = mapperResources()
        assertTrue(mappers.isNotEmpty(), "没有扫到任何 mapper XML，这个测试就没有意义")

        val factory = newBuilderFactory()
        val broken = mappers.mapNotNull { resource ->
            resource.inputStream.use { stream: InputStream ->
                try {
                    factory.newDocumentBuilder().parse(stream)
                    null
                } catch (e: Exception) {
                    "${resource.filename}: ${e.message}"
                }
            }
        }

        assertTrue(broken.isEmpty(), "以下 mapper XML 无法解析，应用会启动失败:\n" + broken.joinToString("\n"))
    }

    @Test
    @DisplayName("注释体里不出现连续的连字符")
    fun `mapper xml comments should not contain consecutive hyphens`() {
        // 单独立一个断言：解析器只报「not well-formed」，定位不到是注释里的 `--`
        val offenders = mapperResources().mapNotNull { resource ->
            val text = resource.inputStream.bufferedReader().readText()
            val hit = Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL)
                .findAll(text)
                .filter { it.groupValues[1].contains("--") }
                .map { text.substring(0, it.range.first).count { c -> c == '\n' } + 1 }
                .toList()
            if (hit.isEmpty()) null else "${resource.filename} 第 ${hit.joinToString(", ")} 行"
        }

        assertTrue(offenders.isEmpty(), "XML 注释体里不能有 `--`，会让 MyBatis 解析失败: " + offenders.joinToString("; "))
    }
}
