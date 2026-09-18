package com.yanban.api.attachment;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

@Component
public class AttachmentParser {
    public static final int MAX_BYTES=10*1024*1024;
    public static final int MAX_TEXT=24000;
    private static final Map<String,String> IMAGES=Map.of("png","image/png","jpg","image/jpeg","jpeg","image/jpeg");
    private static final Set<String> TEXT=Set.of("txt","md","tex","bib","csv","json");
    public String mime(String filename) {
        String ext=extension(filename);
        if(IMAGES.containsKey(ext)) return IMAGES.get(ext);
        if(TEXT.contains(ext)) return "text/plain";
        if(ext.equals("pdf")) return "application/pdf";
        if(ext.equals("doc")) return "application/msword";
        if(ext.equals("docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        throw new IllegalArgumentException("暂不支持该附件格式，请使用 PDF、Word、文本、PNG 或 JPEG");
    }
    public String parse(String filename, String mime, byte[] data) throws Exception {
        if(data.length==0 || data.length>MAX_BYTES) throw new IllegalArgumentException("附件不能为空且不能超过 10 MB");
        if(mime.startsWith("image/")) {
            try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                var readers=ImageIO.getImageReaders(input);
                if(!readers.hasNext()) throw new IllegalArgumentException("图片内容无法识别");
                var reader=readers.next();
                try {
                    reader.setInput(input);
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if(!(mime.equals("image/png") && format.equals("png")) && !(mime.equals("image/jpeg") && (format.equals("jpeg") || format.equals("jpg"))))
                        throw new IllegalArgumentException("图片内容与扩展名不一致");
                    if((long)reader.getWidth(0)*reader.getHeight(0)>25_000_000L) throw new IllegalArgumentException("图片不能超过 2500 万像素");
                    if(reader.read(0)==null) throw new IllegalArgumentException("图片内容不完整");
                } finally { reader.dispose(); }
            }
            return null;
        }
        String text;
        if(TEXT.contains(extension(filename))) {
            text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } else {
            BodyContentHandler handler=new BodyContentHandler(MAX_TEXT);
            new AutoDetectParser().parse(new ByteArrayInputStream(data),handler,new Metadata(),new ParseContext());
            text=handler.toString();
        }
        if(text.length()>MAX_TEXT) throw new IllegalArgumentException("文档超过 24000 字符，请拆分文件或明确加入知识库后检索");
        if(text.isBlank()) throw new IllegalArgumentException("未解析到可读取的正文；扫描件请改用图片或带文本的 PDF");
        return text;
    }
    private String extension(String name) { return name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT); }
}
