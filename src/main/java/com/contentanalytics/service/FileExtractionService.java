package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileExtractionService {

    private final StorageService storageService;

    @Value("${app.storage.local.path:/tmp/content-analytics/uploads}")
    private String localStoragePath;

    /**
     * Extract text from document based on MIME type
     */
    public String extractText(String fileKey, String mimeType) {
        log.info("Extracting text from file: {} (MIME type: {})", fileKey, mimeType);

        try {
            String text = null;

            if ("application/pdf".equals(mimeType)) {
                text = extractFromPDF(fileKey);
            } else if ("text/plain".equals(mimeType)) {
                text = extractFromTXT(fileKey);
            } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)) {
                text = extractFromDOCX(fileKey);
            } else if ("application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(mimeType)) {
                text = extractFromPPTX(fileKey);
            } else {
                throw new RuntimeException("Unsupported file type: " + mimeType);
            }
      
            if (text != null && !text.isBlank()) {
                log.debug("Text extraction successful. Extracted {} characters", text.length());
            } else {
                log.warn("No text extracted from file: {}", fileKey);
            }

            return text;

        } catch (Exception e) {
            log.error("Error extracting text from file: {}", fileKey, e);
            throw new RuntimeException("Failed to extract text from document", e);
        }
    }

    /**
     * Extract text from PDF
     */
    private String extractFromPDF(String fileKey) throws IOException {
        log.debug("Extracting text from PDF: {}", fileKey);

        Path filePath = Paths.get(localStoragePath, fileKey);
        byte[] fileBytes = Files.readAllBytes(filePath);

        try (PDDocument document =
                     PDDocument.load(new ByteArrayInputStream(fileBytes))) {

            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Extract text from plain text file
     */
    private String extractFromTXT(String fileKey) throws IOException {
        log.debug("Extracting text from TXT: {}", fileKey);

        java.nio.file.Path filePath = Paths.get(localStoragePath, fileKey);
        return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
    }

    /**
     * Extract text from DOCX (Word document)
     * DOCX is actually a ZIP file with XML content
     */
    private String extractFromDOCX(String fileKey) throws Exception {
        log.debug("Extracting text from DOCX: {}", fileKey);

        java.nio.file.Path filePath = Paths.get(localStoragePath, fileKey);
        byte[] fileBytes = Files.readAllBytes(filePath);

        StringBuilder text = new StringBuilder();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    // Read and parse document.xml
                    byte[] buffer = new byte[1024];
                    StringBuilder xmlContent = new StringBuilder();
                    int bytesRead;
                    while ((bytesRead = zip.read(buffer)) != -1) {
                        xmlContent.append(new String(buffer, 0, bytesRead));
                    }

                    // Parse XML and extract text
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(new ByteArrayInputStream(xmlContent.toString().getBytes()));

                    // Extract text from <w:t> elements
                    NodeList textElements = doc.getElementsByTagName("w:t");
                    for (int i = 0; i < textElements.getLength(); i++) {
                        Node node = textElements.item(i);
                        if (node.getNodeType() == Node.ELEMENT_NODE) {
                            text.append(node.getTextContent()).append(" ");
                        }
                    }
                }
            }
        }

        return text.toString();
    }

    /**
     * Extract text from PPTX (PowerPoint presentation)
     * PPTX is also a ZIP file with XML slides
     */
    private String extractFromPPTX(String fileKey) throws Exception {
        log.debug("Extracting text from PPTX: {}", fileKey);

        java.nio.file.Path filePath = Paths.get(localStoragePath, fileKey);
        byte[] fileBytes = Files.readAllBytes(filePath);

        StringBuilder text = new StringBuilder();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                // Process all slide files: ppt/slides/slide1.xml, slide2.xml, etc.
                if (entry.getName().startsWith("ppt/slides/slide") && entry.getName().endsWith(".xml")) {
                    byte[] buffer = new byte[1024];
                    StringBuilder xmlContent = new StringBuilder();
                    int bytesRead;
                    while ((bytesRead = zip.read(buffer)) != -1) {
                        xmlContent.append(new String(buffer, 0, bytesRead));
                    }

                    // Parse XML and extract text
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = factory.newDocumentBuilder();
                    Document doc = builder.parse(new ByteArrayInputStream(xmlContent.toString().getBytes()));

                    // Extract text from <a:t> elements
                    NodeList textElements = doc.getElementsByTagName("a:t");
                    for (int i = 0; i < textElements.getLength(); i++) {
                        Node node = textElements.item(i);
                        if (node.getNodeType() == Node.ELEMENT_NODE) {
                            text.append(node.getTextContent()).append(" ");
                        }
                    }
                }
            }
        }

        return text.toString();
    }

}
