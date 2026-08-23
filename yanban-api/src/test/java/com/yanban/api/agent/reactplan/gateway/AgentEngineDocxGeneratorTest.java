package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.DocxBlock;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDocxCreateRequest;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class AgentEngineDocxGeneratorTest {

    private final AgentEngineDocxGenerator generator =
            new AgentEngineDocxGenerator();

    @Test
    void generatesAndReopensOrderedChineseDocumentBlocks() throws Exception {
        var request = request(List.of(
                block("HEADING", "研究报告", 1, null),
                block("PARAGRAPH", "这是正文。", null, null),
                block("TABLE", null, null,
                        List.of(List.of("项目", "结果"), List.of("A", "通过"))),
                block("PAGE_BREAK", null, null, null),
                block("PARAGRAPH", "第二页。", null, null)));

        var generated = generator.generate(request);

        assertThat(generated.mediaType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(generated.bytes()))) {
            assertThat(document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText()).toList())
                    .contains("研究报告", "这是正文。", "第二页。");
            assertThat(document.getTables()).hasSize(1);
            assertThat(document.getTables().get(0).getRow(1).getCell(1).getText())
                    .isEqualTo("通过");
        }
    }

    @Test
    void rejectsNonRectangularTableBeforeWriting() {
        var request = request(List.of(block("TABLE", null, null,
                List.of(List.of("A", "B"), List.of("C")))));

        assertThatThrownBy(() -> generator.generate(request))
                .isInstanceOf(AgentEngineDocxGenerator.AgentEngineDocxException.class)
                .hasMessage("DOCX_BLOCK_INVALID");
    }

    private static WorkspaceDocxCreateRequest request(List<DocxBlock> blocks) {
        return new WorkspaceDocxCreateRequest("1.0", "call.1234567890123456",
                "0".repeat(64), "CREATE", "结果.docx", "结果", "研伴",
                "CHINESE_ACADEMIC", blocks);
    }

    private static DocxBlock block(
            String type, String text, Integer level, List<List<String>> rows) {
        return new DocxBlock(type, text, level, null, null, null,
                "PARAGRAPH".equals(type), "TABLE".equals(type), rows);
    }
}
