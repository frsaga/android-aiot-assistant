package com.example.courseprojectv3;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.courseprojectv3.util.MarkdownRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MarkdownRendererInstrumentedTest {
    @Test
    public void rendersCommonAiMarkdownWithoutRawBlockMarkers() {
        String markdown = "# 一级标题\n"
                + "## 二级标题\n"
                + "### 三级标题\n"
                + "#### 四级标题\n"
                + "##### 五级标题\n"
                + "###### 六级标题\n"
                + "\n"
                + "**粗体**、__粗体2__、*斜体*、_斜体2_、~~删除线~~、`inlineCode`\n"
                + "- 普通无序列表\n"
                + "* 星号列表\n"
                + "+ 加号列表\n"
                + "- [x] 已完成事项\n"
                + "- [ ] 未完成事项\n"
                + "1. 数字列表\n"
                + "2) 另一种数字列表\n"
                + "> 引用内容\n"
                + "---\n"
                + "| 功能 | 位置 |\n"
                + "| --- | --- |\n"
                + "| 对话 | MainActivity |\n"
                + "[链接文本](https://example.com)\n"
                + "![图片说明](https://example.com/a.png)\n"
                + "```java\n"
                + "public class Demo {}\n"
                + "```\n";

        String rendered = MarkdownRenderer.render(markdown).toString();

        assertTrue(rendered.contains("一级标题"));
        assertTrue(rendered.contains("四级标题"));
        assertTrue(rendered.contains("六级标题"));
        assertTrue(rendered.contains("粗体"));
        assertTrue(rendered.contains("斜体"));
        assertTrue(rendered.contains("删除线"));
        assertTrue(rendered.contains("普通无序列表"));
        assertTrue(rendered.contains("[x] 已完成事项"));
        assertTrue(rendered.contains("1. 数字列表"));
        assertTrue(rendered.contains("引用内容"));
        assertTrue(rendered.contains("功能 │ 位置"));
        assertTrue(rendered.contains("对话 │ MainActivity"));
        assertTrue(rendered.contains("──┼─"));
        assertTrue(rendered.contains("链接文本"));
        assertTrue(rendered.contains("图片: 图片说明"));
        assertTrue(rendered.contains("public class Demo {}"));

        assertFalse(rendered.contains("####"));
        assertFalse(rendered.contains("#####"));
        assertFalse(rendered.contains("######"));
        assertFalse(rendered.contains("```"));
        assertFalse(rendered.contains("| --- | --- |"));
        assertFalse(rendered.contains("**粗体**"));
        assertFalse(rendered.contains("~~删除线~~"));
    }

    @Test
    public void rendersHeadingsWithoutRequiredSpace() {
        String rendered = MarkdownRenderer.render("####没有空格的小标题\n######Final").toString();

        assertTrue(rendered.contains("没有空格的小标题"));
        assertTrue(rendered.contains("Final"));
        assertFalse(rendered.contains("####"));
        assertFalse(rendered.contains("######"));
    }

    @Test
    public void rendersCommonAiTableVariantsWithoutRawMarkdown() {
        String markdown = "功能 | 状态 | 说明\n"
                + ":--- | :---: | ---:\n"
                + "图片 | ✅ | 显示缩略图\n"
                + "文档 |  | 保留文件名\n"
                + "表达式 | `a | b` | 支持代码竖线\n"
                + "转义 | A \\| B | 支持转义竖线\n";

        String rendered = MarkdownRenderer.render(markdown).toString();

        assertTrue(rendered.contains("功能"));
        assertTrue(rendered.contains("状态"));
        assertTrue(rendered.contains("显示缩略图"));
        assertTrue(rendered.contains("A | B"));
        assertTrue(rendered.contains("a | b"));
        assertTrue(rendered.contains("• 图片"));
        assertTrue(rendered.contains("状态：✅"));
        assertTrue(rendered.contains("说明：显示缩略图"));
        assertFalse(rendered.contains(":---"));
        assertFalse(rendered.contains("\\|"));
        assertFalse(rendered.contains("┼"));
    }

    @Test
    public void convertsWideTablesToMobileFriendlyFieldGroups() {
        String markdown = "功能 | 关键说明 | Android 开发关联点\n"
                + "--- | --- | ---\n"
                + "多格式打开 | Word/PPT/Keynote/CAD | ACTION_VIEW 与 FileProvider\n"
                + "全文搜索 | 标题和内容双索引 | Room FTS 或 SQLite FTS5\n";

        String rendered = MarkdownRenderer.render(markdown).toString();

        assertTrue(rendered.contains("• 多格式打开"));
        assertTrue(rendered.contains("关键说明：Word/PPT/Keynote/CAD"));
        assertTrue(rendered.contains("Android 开发关联点：ACTION_VIEW 与 FileProvider"));
        assertTrue(rendered.contains("• 全文搜索"));
        assertFalse(rendered.contains("─┼─"));
    }

    @Test
    public void rendersHtmlBreakTagsAsLineBreaks() {
        String rendered = MarkdownRenderer.render("第一行<br>第二行<br />第三行").toString();

        assertTrue(rendered.contains("第一行\n第二行\n第三行"));
        assertFalse(rendered.contains("<br"));
    }
}
