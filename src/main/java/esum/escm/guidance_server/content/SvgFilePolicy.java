package esum.escm.guidance_server.content;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validate static diagrams without rewriting their vector geometry, labels, or styles. */
final class SvgFilePolicy {
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final String XHTML = "http://www.w3.org/1999/xhtml";
    private static final Set<String> SVG_ELEMENTS = Set.of(
            "svg", "g", "defs", "symbol", "use", "a", "title", "desc", "metadata", "style", "foreignObject",
            "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "tspan", "textPath",
            "image", "switch", "view", "marker", "clipPath", "mask", "pattern", "linearGradient", "radialGradient", "stop",
            "filter", "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix",
            "feDiffuseLighting", "feDisplacementMap", "feDistantLight", "feDropShadow", "feFlood",
            "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feImage", "feMerge", "feMergeNode",
            "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight", "feTile", "feTurbulence");
    // Mermaid exports can use XHTML for labels inside foreignObject.
    private static final Set<String> LABEL_ELEMENTS = Set.of(
            "div", "span", "p", "br", "b", "strong", "i", "em", "u", "s", "small", "code", "pre", "sub", "sup",
            "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th");
    private static final Pattern CSS_URL = Pattern.compile("url\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    static void validate(MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.parse(input, new DiagramHandler());
        } catch (ParserConfigurationException error) {
            throw new IllegalStateException("Secure SVG parser is unavailable", error);
        } catch (SAXException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Upload a valid static SVG diagram without scripts, external resources, or DOCTYPE declarations");
        }
    }

    private static class DiagramHandler extends DefaultHandler {
        int depth;
        int elements;
        int foreignDepth;
        int metadataDepth;
        StringBuilder style;

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes) throws SAXException {
            if (++depth > 256 || ++elements > 200_000) throw invalid();
            if (depth == 1 && (!SVG.equals(uri) || !"svg".equals(name))) throw invalid();
            if (SVG.equals(uri)) {
                if (!SVG_ELEMENTS.contains(name)) throw invalid();
                if (name.equals("foreignObject")) foreignDepth++;
                if (name.equals("metadata")) metadataDepth++;
                if (name.equals("style")) {
                    if (style != null) throw invalid();
                    style = new StringBuilder();
                }
            } else if (XHTML.equals(uri)) {
                if (foreignDepth == 0 || !LABEL_ELEMENTS.contains(name)) throw invalid();
            } else if (metadataDepth == 0) {
                throw invalid();
            }
            for (int i = 0; i < attributes.getLength(); i++) {
                String attribute = attributes.getLocalName(i).toLowerCase(Locale.ROOT);
                String value = attributes.getValue(i).trim();
                if (attribute.startsWith("on") || (XMLConstants.XML_NS_URI.equals(attributes.getURI(i)) && attribute.equals("base"))) throw invalid();
                if (SVG.equals(uri) && name.equals("a") && attribute.equals("href")) {
                    requireNavigationReference(value);
                } else if (Set.of("href", "src", "background").contains(attribute)) {
                    requireLocalReference(value);
                }
                if (attribute.equals("style") || CSS_URL.matcher(value).find()) validateCss(value);
            }
        }

        @Override public void characters(char[] buffer, int start, int length) {
            if (style != null) style.append(buffer, start, length);
        }

        @Override public void endElement(String uri, String name, String qualified) throws SAXException {
            if (SVG.equals(uri) && name.equals("style")) {
                validateCss(style.toString());
                style = null;
            }
            if (SVG.equals(uri) && name.equals("foreignObject")) foreignDepth--;
            if (SVG.equals(uri) && name.equals("metadata")) metadataDepth--;
            depth--;
        }

        @Override public void processingInstruction(String target, String data) throws SAXException {
            // PlantUML embeds its version and compressed source as inert export metadata.
            // Preserve it without interpreting or decompressing it. Other instructions remain blocked.
            if (target.equals("plantuml") && data.trim().matches("[0-9]+(?:\\.[0-9]+)+(?:[A-Za-z0-9._-]*)")) return;
            if (target.equals("plantuml-src") && data.trim().matches("[A-Za-z0-9_-]+")) return;
            throw invalid();
        }
        @Override public void error(SAXParseException error) throws SAXException { throw error; }
        @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
    }

    private static void validateCss(String value) throws SAXException {
        String css = value.replaceAll("(?s)/\\*.*?\\*/", "");
        String lower = css.toLowerCase(Locale.ROOT);
        // Keep ordinary exporter CSS (including media/keyframe rules), but forbid external imports.
        if (css.contains("\\") || lower.contains("@import") || lower.contains("expression(")
                || lower.contains("-moz-binding") || lower.contains("behavior:")) throw invalid();
        var urls = CSS_URL.matcher(css);
        while (urls.find()) {
            String reference = urls.group(1).trim();
            if (reference.length() >= 2 && ((reference.startsWith("\"") && reference.endsWith("\""))
                    || (reference.startsWith("'") && reference.endsWith("'")))) reference = reference.substring(1, reference.length() - 1).trim();
            requireLocalReference(reference);
        }
    }

    private static void requireLocalReference(String value) throws SAXException {
        if (value.startsWith("#")) return;
        if (value.matches("(?is)data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=\\s]+")) return;
        throw invalid();
    }

    private static void requireNavigationReference(String value) throws SAXException {
        if (value.startsWith("#")) return;
        try {
            URI link = URI.create(value);
            if (("http".equalsIgnoreCase(link.getScheme()) || "https".equalsIgnoreCase(link.getScheme()))
                    && link.getHost() != null && link.getRawUserInfo() == null) return;
        } catch (IllegalArgumentException ignored) {
            // Reject malformed URLs along with script/data/file schemes.
        }
        throw invalid();
    }

    private static SAXException invalid() { return new SAXException("Unsupported SVG content"); }
}
