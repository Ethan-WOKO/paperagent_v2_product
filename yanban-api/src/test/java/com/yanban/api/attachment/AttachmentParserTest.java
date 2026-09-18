package com.yanban.api.attachment;
import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
class AttachmentParserTest {
    AttachmentParser parser=new AttachmentParser();
    @Test void readsUtf8WithoutDroppingNonLatinCharacters() throws Exception {
        assertThat(parser.parse("a.txt","text/plain","你好 café".getBytes(StandardCharsets.UTF_8))).isEqualTo("你好 café");
    }
    @Test void rejectsInvalidUtf8AndEmptyText() {
        assertThatThrownBy(()->parser.parse("a.txt","text/plain",new byte[]{(byte)255})).isInstanceOf(java.nio.charset.CharacterCodingException.class);
        assertThatThrownBy(()->parser.parse("a.txt","text/plain","  ".getBytes())).hasMessageContaining("正文");
    }
    @Test void rejectsDisguisedAndOversizedFiles() {
        assertThatThrownBy(()->parser.mime("malicious.svg")).hasMessageContaining("格式");
        assertThatThrownBy(()->parser.parse("a.png","image/png","not an image".getBytes())).hasMessageContaining("图片");
        assertThatThrownBy(()->parser.parse("a.txt","text/plain",new byte[AttachmentParser.MAX_BYTES+1])).hasMessageContaining("10 MB");
    }
    @Test void validatesRealImageAndRejectsWrongExtension() throws Exception {
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",output);
        assertThat(parser.parse("a.png","image/png",output.toByteArray())).isNull();
        assertThatThrownBy(()->parser.parse("a.jpg","image/jpeg",output.toByteArray())).hasMessageContaining("不一致");
    }
}
