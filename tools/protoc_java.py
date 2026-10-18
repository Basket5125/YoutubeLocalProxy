#!/usr/bin/env python3
"""
Minimal protoc replacement: compiles the .proto files in app/src/main/proto to plain Java
classes (package com.viitube.proxy.proto) that need no protobuf library, so they run on
Android 2.3+. Only what those files use is supported: proto3 messages, nested messages and
enums, optional/repeated fields, imports and scalar types.

Encoding follows google-protobuf's JavaScript output (the gdatav2innertube server):
fields are written in declaration order; proto3 scalars without "optional" only when not
default; "optional" scalars and message fields whenever set; repeated numbers packed.

Usage: python3 tools/protoc_java.py   (regenerates every file; commit the output)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROTO_DIR = os.path.join(ROOT, "app", "src", "main", "proto")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "java", "com", "viitube", "proxy", "proto")
PACKAGE = "com.viitube.proxy.proto"

SCALARS = {
    # proto type: (java type, default, wire type, writer, reader, packable)
    "string": ("String", '""', 2, "writeString", "readString", False),
    "bytes": ("byte[]", "EMPTY_BYTES", 2, "writeBytes", "readBytes", False),
    "bool": ("boolean", "false", 0, "writeBool", "readBool", True),
    "int32": ("int", "0", 0, "writeInt32", "readInt32", True),
    "uint32": ("int", "0", 0, "writeUInt32", "readInt32", True),
    "sint32": ("int", "0", 0, "writeSInt32", "readSInt32", True),
    "int64": ("long", "0L", 0, "writeInt64", "readInt64", True),
    "uint64": ("long", "0L", 0, "writeInt64", "readInt64", True),
    "sint64": ("long", "0L", 0, "writeSInt64", "readSInt64", True),
    "fixed32": ("int", "0", 5, "writeFixed32", "readFixed32", True),
    "sfixed32": ("int", "0", 5, "writeFixed32", "readFixed32", True),
    "fixed64": ("long", "0L", 1, "writeFixed64", "readFixed64", True),
    "sfixed64": ("long", "0L", 1, "writeFixed64", "readFixed64", True),
}
BOXED = {"boolean": "Boolean", "int": "Integer", "long": "Long"}
JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue
default do double else enum extends final finally float for goto if implements import
instanceof int interface long native new package private protected public return short static
strictfp super switch synchronized this throw throws transient try void volatile while true
false null""".split())


class Field:
    def __init__(self, label, type_name, name, number):
        self.label = label  # "", "optional" or "repeated"
        self.type_name = type_name
        self.name = name
        self.number = number
        self.resolved = None  # ("scalar", t) | ("enum", Enum) | ("message", Message)


class Enum:
    def __init__(self, name, parent, proto_file):
        self.name = name
        self.parent = parent
        self.file = proto_file
        self.values = []
        self.java_name = None

    def full_name(self):
        return (self.parent.full_name() if self.parent else self.file.package_prefix()) + self.name


class Message:
    def __init__(self, name, parent, proto_file):
        self.name = name
        self.parent = parent
        self.file = proto_file
        self.fields = []
        self.messages = []
        self.enums = []
        self.java_name = None

    def full_name(self):
        return (self.parent.full_name() if self.parent else self.file.package_prefix()) + self.name + "."


class ProtoFile:
    def __init__(self, path):
        self.path = path
        self.package = ""
        self.imports = []
        self.messages = []
        self.enums = []
        base = os.path.splitext(os.path.basename(path))[0]
        outer = "".join(p[:1].upper() + p[1:] for p in re.split(r"[_\-.]", base) if p)
        self.outer = outer if outer.endswith("Proto") else outer + "Proto"

    def package_prefix(self):
        return self.package + "." if self.package else ""


def tokenize(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return re.findall(r'"[^"]*"|[A-Za-z_][A-Za-z0-9_.]*|-?\d+|[{}=;\[\](),<>]', text)


def parse(path):
    proto = ProtoFile(path)
    tokens = tokenize(open(path, encoding="utf-8").read())
    pos = [0]

    def peek():
        return tokens[pos[0]] if pos[0] < len(tokens) else None

    def take(expected=None):
        token = tokens[pos[0]]
        pos[0] += 1
        if expected is not None and token != expected:
            sys.exit("%s: expected %r, got %r" % (path, expected, token))
        return token

    def parse_enum(parent):
        enum = Enum(take(), parent, proto)
        take("{")
        while peek() != "}":
            if peek() == "option":
                while take() != ";":
                    pass
                continue
            name = take()
            take("=")
            enum.values.append((name, int(take())))
            take(";")
        take("}")
        return enum

    def parse_message(parent):
        message = Message(take(), parent, proto)
        take("{")
        while peek() != "}":
            token = peek()
            if token == "message":
                take()
                message.messages.append(parse_message(message))
            elif token == "enum":
                take()
                message.enums.append(parse_enum(message))
            elif token in ("oneof", "map", "extensions", "extend", "group"):
                sys.exit("%s: unsupported construct %r in %s" % (path, token, message.name))
            elif token == "reserved" or token == "option":
                while take() != ";":
                    pass
            elif token == ";":
                take()
            else:
                label = ""
                if token in ("optional", "repeated", "required"):
                    label = take()
                    if label == "required":
                        label = ""
                type_name = take()
                name = take()
                take("=")
                number = int(take())
                if peek() == "[":
                    while take() != "]":
                        pass
                take(";")
                message.fields.append(Field(label, type_name, name, number))
        take("}")
        return message

    while peek() is not None:
        token = take()
        if token == "syntax":
            take("=")
            syntax = take()
            if syntax.strip('"') != "proto3":
                sys.exit("%s: only proto3 is supported" % path)
            take(";")
        elif token == "package":
            proto.package = take()
            take(";")
        elif token == "import":
            proto.imports.append(take().strip('"'))
            take(";")
        elif token == "option":
            while take() != ";":
                pass
        elif token == "message":
            proto.messages.append(parse_message(None))
        elif token == "enum":
            proto.enums.append(parse_enum(None))
        elif token == ";":
            pass
        else:
            sys.exit("%s: unexpected token %r" % (path, token))
    return proto


def walk(container_messages, container_enums):
    for enum in container_enums:
        yield enum
    for message in container_messages:
        yield message
        for item in walk(message.messages, message.enums):
            yield item


def capitalize(name):
    return name[:1].upper() + name[1:]


def camel(name):
    parts = name.split("_")
    return parts[0] + "".join(capitalize(p) for p in parts[1:])


def assign_java_names(proto):
    """Java forbids a nested class named like an enclosing class; such names get the parent's name in front."""

    def assign(items, enclosing_names, parent_java):
        used = set()
        for item in items:
            name = capitalize(item.name)
            while name in enclosing_names or name in used or name == proto.outer:
                name = parent_java + name
            used.add(name)
            item.java_name = name

    def visit(message, enclosing):
        names = enclosing | {message.java_name}
        assign(message.enums + message.messages, names, message.java_name)
        for child in message.messages:
            visit(child, names)

    assign(proto.enums + proto.messages, {proto.outer}, proto.outer)
    for message in proto.messages:
        visit(message, {proto.outer})


def java_ref(item):
    """Fully qualified Java name relative to the package, e.g. AndroidSearchProto.Root.Contents."""
    parts = []
    node = item
    while node is not None:
        parts.append(node.java_name)
        node = node.parent
    parts.append(item.file.outer)
    return ".".join(reversed(parts))


def resolve(files):
    types = {}
    for proto in files:
        for item in walk(proto.messages, proto.enums):
            full = item.full_name().rstrip(".")
            if full in types:
                sys.exit("duplicate type " + full)
            types[full] = item

    for proto in files:
        visible = {proto.path} | {os.path.join(PROTO_DIR, i) for i in proto.imports}
        for item in walk(proto.messages, proto.enums):
            if not isinstance(item, Message):
                continue
            for field in item.fields:
                if field.type_name in SCALARS:
                    field.resolved = ("scalar", field.type_name)
                    continue
                found = None
                if field.type_name.startswith("."):
                    found = types.get(field.type_name[1:])
                else:
                    # Innermost scope first, as protoc does.
                    scope = item.full_name().rstrip(".").split(".")
                    while found is None:
                        candidate = ".".join(scope + [field.type_name]) if scope else field.type_name
                        found = types.get(candidate)
                        if not scope:
                            break
                        scope.pop()
                if found is None or found.file.path not in visible:
                    sys.exit("%s: cannot resolve type %s of %s.%s"
                             % (proto.path, field.type_name, item.name, field.name))
                field.resolved = ("enum" if isinstance(found, Enum) else "message", found)


def field_java_type(field):
    kind, target = field.resolved
    if kind == "scalar":
        return SCALARS[target][0]
    if kind == "enum":
        return "int"
    return java_ref(target)


def emit_enum(enum, indent):
    pad = " " * indent
    out = [pad + "public static final class %s {" % enum.java_name]
    out.append(pad + "    private %s() { }" % enum.java_name)
    for name, value in enum.values:
        out.append(pad + "    public static final int %s = %d;" % (name, value))
    out.append(pad + "}")
    return out


def emit_message(message, indent):
    pad = " " * indent
    inner = pad + "    "
    cls = message.java_name
    out = ["", pad + "/** proto: %s */" % message.full_name().rstrip("."),
           pad + "public static final class %s extends ProtoMessage {" % cls]
    for enum in message.enums:
        out.extend(emit_enum(enum, indent + 4))
    for child in message.messages:
        out.extend(emit_message(child, indent + 4))

    # Storage
    for field in message.fields:
        jtype = field_java_type(field)
        member = "m" + capitalize(camel(field.name))
        kind = field.resolved[0]
        if field.label == "repeated":
            boxed = BOXED.get(jtype, jtype)
            out.append(inner + "private final java.util.List<%s> %s = new java.util.ArrayList<%s>();"
                       % (boxed, member, boxed))
        elif kind == "message":
            out.append(inner + "private %s %s;" % (jtype, member))
        else:
            default = SCALARS[field.resolved[1]][1] if kind == "scalar" else "0"
            out.append(inner + "private %s %s = %s;" % (jtype, member, default))
            if field.label == "optional":
                out.append(inner + "private boolean has%s;" % capitalize(camel(field.name)))

    # Accessors
    for field in message.fields:
        jtype = field_java_type(field)
        prop = capitalize(camel(field.name))
        member = "m" + prop
        kind = field.resolved[0]
        if field.label == "repeated":
            boxed = BOXED.get(jtype, jtype)
            out.append(inner + "public %s add%s(%s value) {" % (cls, prop, jtype))
            if kind == "message" or jtype in ("String", "byte[]"):
                out.append(inner + "    if (value == null) throw new NullPointerException(\"%s\");"
                           % field.name)
            out.append(inner + "    %s.add(value);" % member)
            out.append(inner + "    return this;")
            out.append(inner + "}")
            out.append(inner + "public java.util.List<%s> get%sList() { return %s; }" % (boxed, prop, member))
            out.append(inner + "public int get%sCount() { return %s.size(); }" % (prop, member))
            out.append(inner + "public %s get%s(int index) { return %s.get(index); }" % (jtype, prop, member))
        elif kind == "message":
            out.append(inner + "public %s set%s(%s value) { %s = value; return this; }"
                       % (cls, prop, jtype, member))
            out.append(inner + "public %s get%s() { return %s; }" % (jtype, prop, member))
            out.append(inner + "public boolean has%s() { return %s != null; }" % (prop, member))
        else:
            if jtype in ("String", "byte[]"):
                default = SCALARS[field.resolved[1]][1]
                assign = "%s = value == null ? %s : value;" % (member, default)
            else:
                assign = "%s = value;" % member
            if field.label == "optional":
                assign += " has%s = true;" % prop
            out.append(inner + "public %s set%s(%s value) { %s return this; }" % (cls, prop, jtype, assign))
            out.append(inner + "public %s get%s() { return %s; }" % (jtype, prop, member))
            if field.label == "optional":
                out.append(inner + "public boolean has%s() { return has%s; }" % (prop, prop))

    # Serialization, in declaration order like google-protobuf's JavaScript code.
    out.append("")
    out.append(inner + "@Override")
    out.append(inner + "public void writeTo(ProtoWriter writer) {")
    for field in message.fields:
        prop = capitalize(camel(field.name))
        member = "m" + prop
        kind, target = field.resolved
        num = field.number
        if kind == "message":
            if field.label == "repeated":
                out.append(inner + "    for (int i = 0; i < %s.size(); i++) writer.writeMessage(%d, %s.get(i));"
                           % (member, num, member))
            else:
                out.append(inner + "    if (%s != null) writer.writeMessage(%d, %s);" % (member, num, member))
            continue
        scalar = target if kind == "scalar" else "int32"
        jtype, default, wire, writer_fn, reader_fn, packable = SCALARS[scalar]
        if field.label == "repeated":
            if packable:
                out.append(inner + "    if (!%s.isEmpty()) {" % member)
                out.append(inner + "        ProtoWriter packed = new ProtoWriter();")
                out.append(inner + "        for (int i = 0; i < %s.size(); i++) packed.%sNoTag(%s.get(i));"
                           % (member, writer_fn, member))
                out.append(inner + "        writer.writeBytes(%d, packed.toByteArray());" % num)
                out.append(inner + "    }")
            else:
                out.append(inner + "    for (int i = 0; i < %s.size(); i++) writer.%s(%d, %s.get(i));"
                           % (member, writer_fn, num, member))
        elif field.label == "optional":
            out.append(inner + "    if (has%s) writer.%s(%d, %s);" % (prop, writer_fn, num, member))
        else:
            if jtype == "String":
                cond = "%s.length() > 0" % member
            elif jtype == "byte[]":
                cond = "%s.length > 0" % member
            elif jtype == "boolean":
                cond = member
            else:
                cond = "%s != %s" % (member, default)
            out.append(inner + "    if (%s) writer.%s(%d, %s);" % (cond, writer_fn, num, member))
    out.append(inner + "}")

    # Parsing
    out.append("")
    out.append(inner + "@Override")
    out.append(inner + "public void mergeFrom(ProtoReader reader) throws java.io.IOException {")
    out.append(inner + "    while (reader.hasMore()) {")
    out.append(inner + "        int tag = reader.readTag();")
    out.append(inner + "        switch (tag >>> 3) {")
    for field in message.fields:
        prop = capitalize(camel(field.name))
        member = "m" + prop
        kind, target = field.resolved
        out.append(inner + "            case %d:" % field.number)
        if kind == "message":
            jtype = java_ref(target)
            out.append(inner + "                if ((tag & 7) != 2) { reader.skip(tag); break; }")
            if field.label == "repeated":
                out.append(inner + "                { %s item = new %s(); item.mergeFrom(reader.readMessage()); %s.add(item); }"
                           % (jtype, jtype, member))
            else:
                out.append(inner + "                if (%s == null) %s = new %s();" % (member, member, jtype))
                out.append(inner + "                %s.mergeFrom(reader.readMessage());" % member)
            out.append(inner + "                break;")
            continue
        scalar = target if kind == "scalar" else "int32"
        jtype, default, wire, writer_fn, reader_fn, packable = SCALARS[scalar]
        if field.label == "repeated":
            if packable:
                out.append(inner + "                if ((tag & 7) == 2) {")
                out.append(inner + "                    ProtoReader packed = reader.readMessage();")
                out.append(inner + "                    while (packed.hasMore()) %s.add(packed.%s());" % (member, reader_fn))
                out.append(inner + "                } else if ((tag & 7) == %d) {" % wire)
                out.append(inner + "                    %s.add(reader.%s());" % (member, reader_fn))
                out.append(inner + "                } else reader.skip(tag);")
            else:
                out.append(inner + "                if ((tag & 7) != %d) { reader.skip(tag); break; }" % wire)
                out.append(inner + "                %s.add(reader.%s());" % (member, reader_fn))
        else:
            out.append(inner + "                if ((tag & 7) != %d) { reader.skip(tag); break; }" % wire)
            out.append(inner + "                %s = reader.%s();" % (member, reader_fn))
            if field.label == "optional":
                out.append(inner + "                has%s = true;" % prop)
        out.append(inner + "                break;")
    out.append(inner + "            default:")
    out.append(inner + "                reader.skip(tag);")
    out.append(inner + "        }")
    out.append(inner + "    }")
    out.append(inner + "}")

    out.append("")
    out.append(inner + "public static %s parseFrom(byte[] data) throws java.io.IOException {" % cls)
    out.append(inner + "    %s message = new %s();" % (cls, cls))
    out.append(inner + "    message.mergeFrom(new ProtoReader(data, 0, data.length));")
    out.append(inner + "    return message;")
    out.append(inner + "}")
    out.append(pad + "}")
    return out


def emit_file(proto):
    rel = os.path.relpath(proto.path, PROTO_DIR)
    lines = ["// Generated by tools/protoc_java.py from app/src/main/proto/%s - do not edit." % rel,
             "package %s;" % PACKAGE, "",
             "@SuppressWarnings({\"unused\", \"WeakerAccess\"})",
             "public final class %s {" % proto.outer,
             "    private %s() { }" % proto.outer]
    for enum in proto.enums:
        lines.extend(emit_enum(enum, 4))
    for message in proto.messages:
        lines.extend(emit_message(message, 4))
    lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    paths = sorted(os.path.join(PROTO_DIR, n) for n in os.listdir(PROTO_DIR) if n.endswith(".proto"))
    files = [parse(p) for p in paths]
    for proto in files:
        assign_java_names(proto)
    resolve(files)
    os.makedirs(OUT_DIR, exist_ok=True)
    keep = {"ProtoMessage.java", "ProtoWriter.java", "ProtoReader.java"}
    for name in os.listdir(OUT_DIR):
        if name.endswith("Proto.java") and name not in keep:
            os.remove(os.path.join(OUT_DIR, name))
    for proto in files:
        with open(os.path.join(OUT_DIR, proto.outer + ".java"), "w", encoding="utf-8") as out:
            out.write(emit_file(proto))
        print("generated", proto.outer + ".java")


if __name__ == "__main__":
    main()
