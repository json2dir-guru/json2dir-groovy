// json2dir: create the directory tree a JSON document describes, in the current directory.
// Implements RFC J2D-1 (https://github.com/kitsunoff/awesome-json2dir/blob/main/spec/rfc-json2dir.md).
// Groovy + JDK only: the JSON parser is hand-written (no JsonSlurper).

import groovy.transform.CompileStatic

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

@CompileStatic
class Fail extends RuntimeException {
    Fail(String message) { super(message) }
}

// Values that have no meaning in json2dir; kept only to be reported by validate().
@CompileStatic
class Other {
    final String what
    Other(String what) { this.what = what }
}

// §3: strict JSON over an already strictly decoded UTF-8 string.
@CompileStatic
class Parser {
    final String s
    int i = 0

    Parser(String s) {
        this.s = s
        // A leading byte order mark is ignored, as §3 allows.
        if (s.length() > 0 && (int) s.charAt(0) == 0xFEFF) i = 1
    }

    Fail error(String what) { new Fail('input is not valid JSON: ' + what + ' at offset ' + i) }

    int peek() { i < s.length() ? (int) s.charAt(i) : -1 }

    void ws() {
        while (true) {
            int c = peek()
            if (c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D) i++
            else break
        }
    }

    Object document() {
        ws()
        Object v = value()
        ws()
        if (i != s.length()) throw error('unexpected data after the value')
        v
    }

    Object value() {
        int c = peek()
        if (c < 0) throw error('unexpected end of input')
        if (c == ('{' as char)) return object()
        if (c == ('[' as char)) return array()
        if (c == ('"' as char)) return string()
        if (c == ('t' as char)) { literal('true'); return new Other('true') }
        if (c == ('f' as char)) { literal('false'); return new Other('false') }
        if (c == ('n' as char)) { literal('null'); return new Other('null') }
        if (c == ('-' as char) || (c >= 0x30 && c <= 0x39)) { number(); return new Other('number') }
        throw error('unexpected character')
    }

    void literal(String word) {
        if (!s.startsWith(word, i)) throw error('invalid literal')
        i += word.length()
    }

    boolean digits() {
        int start = i
        while (peek() >= 0x30 && peek() <= 0x39) i++
        i > start
    }

    void number() {
        if (peek() == ('-' as char)) i++
        if (peek() == 0x30) i++
        else if (!digits()) throw error('invalid number')
        if (peek() == ('.' as char)) { i++; if (!digits()) throw error('invalid number') }
        if (peek() == ('e' as char) || peek() == ('E' as char)) {
            i++
            if (peek() == ('+' as char) || peek() == ('-' as char)) i++
            if (!digits()) throw error('invalid number')
        }
    }

    Map<String, Object> object() {
        i++ // {
        Map<String, Object> map = new LinkedHashMap<String, Object>()
        ws()
        if (peek() == ('}' as char)) { i++; return map }
        while (true) {
            ws()
            if (peek() != ('"' as char)) throw error('expected a member name')
            String name = string()
            ws()
            if (peek() != (':' as char)) throw error("expected ':'")
            i++
            ws()
            Object v = value()
            map.remove(name) // duplicates: the last occurrence wins
            map.put(name, v)
            ws()
            int c = peek()
            if (c < 0) throw error('unexpected end of input')
            if (c == ('}' as char)) { i++; return map }
            if (c != (',' as char)) throw error("expected ',' or '}'")
            i++
        }
    }

    List<Object> array() {
        i++ // [
        List<Object> list = new ArrayList<Object>()
        ws()
        if (peek() == (']' as char)) { i++; return list }
        while (true) {
            ws()
            list.add(value())
            ws()
            int c = peek()
            if (c < 0) throw error('unexpected end of input')
            if (c == (']' as char)) { i++; return list }
            if (c != (',' as char)) throw error("expected ',' or ']'")
            i++
        }
    }

    String string() {
        i++ // opening quote
        StringBuilder b = new StringBuilder()
        while (true) {
            int c = peek()
            if (c < 0) throw error('unterminated string')
            if (c == ('"' as char)) { i++; break }
            if (c < 0x20) throw error('control character in string')
            i++
            if (c != ('\\' as char)) { b.append((char) c); continue }
            int e = peek()
            if (e < 0) throw error('unterminated string')
            i++
            switch ((char) e) {
                case ('"' as char): b.append('"'); break
                case ('\\' as char): b.append('\\'); break
                case ('/' as char): b.append('/'); break
                case ('b' as char): b.append('\b'); break
                case ('f' as char): b.append('\f'); break
                case ('n' as char): b.append('\n'); break
                case ('r' as char): b.append('\r'); break
                case ('t' as char): b.append('\t'); break
                case ('u' as char):
                    if (i + 4 > s.length()) throw error('invalid \\u escape')
                    int code = 0
                    for (int k = 0; k < 4; k++) {
                        char h = s.charAt(i + k)
                        int d = Character.digit(h, 16)
                        if (d < 0 || (int) h > 0x7F) throw error('invalid \\u escape')
                        code = code * 16 + d
                    }
                    i += 4
                    b.append((char) code)
                    break
                default:
                    i--
                    throw error('invalid escape')
            }
        }
        String r = b.toString()
        // §3.5: an escape that denotes an unpaired surrogate cannot be encoded as UTF-8.
        for (int k = 0; k < r.length(); k++) {
            char c = r.charAt(k)
            if (Character.isHighSurrogate(c) && k + 1 < r.length() && Character.isLowSurrogate(r.charAt(k + 1))) k++
            else if (Character.isSurrogate(c)) throw new Fail('input contains an unpaired surrogate')
        }
        r
    }
}

@CompileStatic
class Json2dir {
    static String decode(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (CharacterCodingException ignored) {
            throw new Fail('input is not valid UTF-8')
        }
    }

    static String quote(String name) { '"' + name.replace('\\', '\\\\').replace('"', '\\"') + '"' }

    // §4, §6: validate the whole document before touching the file system.
    static void validate(Object value, String where) {
        if (value instanceof String) return
        if (value instanceof List) {
            List list = (List) value
            if (list.size() != 2 || !(list.get(0) instanceof String) || !(list.get(1) instanceof String))
                throw new Fail(where + ': an array must be ["link", target] or ["script", content]')
            String kind = (String) list.get(0)
            String payload = (String) list.get(1)
            if (kind != 'link' && kind != 'script')
                throw new Fail(where + ': unknown array kind ' + quote(kind))
            if (kind == 'link' && payload.indexOf(0) >= 0)
                throw new Fail(where + ': a link target cannot contain NUL')
            return
        }
        if (value instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
                String name = e.getKey()
                String child = where == '.' ? name : where + '/' + name
                // §4.2.1: names are used exactly; trailing "/" or "/." forms are rejected, not trimmed.
                if (name.isEmpty() || name == '.' || name == '..' || name.indexOf('/') >= 0 || name.indexOf(0) >= 0)
                    throw new Fail(child + ': invalid name ' + quote(name))
                validate(e.getValue(), child)
            }
            return
        }
        throw new Fail(where + ': ' + ((Other) value).what + ' values are not allowed')
    }

    static byte[] utf8(String s) { s.getBytes(StandardCharsets.UTF_8) }

    static BasicFileAttributes lstat(Path p) {
        try {
            Files.readAttributes(p, BasicFileAttributes, LinkOption.NOFOLLOW_LINKS)
        } catch (NoSuchFileException ignored) {
            null
        }
    }

    // §5.2, §5.3: an existing non-directory is removed (a symlink itself, never its target);
    // §5.4: a directory in the way of a non-object is an error.
    static void clear(Path p, BasicFileAttributes existing) {
        if (existing == null) return
        if (existing.isDirectory()) throw new Fail(p.toString() + ': a directory is in the way')
        Files.delete(p)
    }

    static void writeFile(Path p, String content, boolean executable) {
        // CREATE_NEW = O_CREAT | O_EXCL, mode 0666 & ~umask.
        SeekableByteChannel ch = Files.newByteChannel(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            ByteBuffer buf = ByteBuffer.wrap(utf8(content))
            while (buf.hasRemaining()) ch.write(buf)
        } finally {
            ch.close()
        }
        if (executable) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS)
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            perms.add(PosixFilePermission.GROUP_EXECUTE)
            perms.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(p, perms)
        }
    }

    static int compareBytes(String a, String b) { Arrays.compareUnsigned(utf8(a), utf8(b)) }

    static void apply(Path dir, Map<String, Object> tree) {
        // Members in ascending order of the UTF-8 bytes of their names, like the reference.
        List<String> names = new ArrayList<String>(tree.keySet())
        names.sort({ String a, String b -> compareBytes(a, b) } as Comparator<String>)
        for (String name : names) {
            Path p = dir.resolve(name)
            Object value = tree.get(name)
            BasicFileAttributes existing = lstat(p)
            if (value instanceof String) {
                clear(p, existing)
                writeFile(p, (String) value, false)
            } else if (value instanceof List) {
                List list = (List) value
                clear(p, existing)
                // Note: java.nio normalizes the target path (e.g. "a//b" -> "a/b", trailing "/" dropped).
                if (list.get(0) == 'link') Files.createSymbolicLink(p, Paths.get((String) list.get(1)))
                else writeFile(p, (String) list.get(1), true)
            } else {
                if (existing == null || !existing.isDirectory()) {
                    if (existing != null) Files.delete(p)
                    Files.createDirectory(p)
                }
                apply(p, (Map<String, Object>) value)
            }
        }
    }

    static void main(String[] args) {
        if (args.length > 0) {
            System.err.println('usage: json2dir < document.json')
            System.exit(2)
        }
        try {
            Object document = new Parser(decode(System.in.readAllBytes())).document()
            if (!(document instanceof Map)) throw new Fail('the root of the document must be an object')
            validate(document, '.')
            apply(Paths.get(''), (Map<String, Object>) document)
            System.exit(0)
        } catch (Fail e) {
            System.err.println('json2dir: ' + e.getMessage())
        } catch (StackOverflowError ignored) {
            System.err.println('json2dir: the document is nested too deeply')
        } catch (Exception e) {
            System.err.println('json2dir: ' + e)
        }
        System.exit(1)
    }
}
