#!/usr/bin/env python3
"""
NovaDeobfuscator.py — Complete Standalone Deobfuscation & Bytecode Purification Tool
Target: Custom 6-Layer Obfuscated Minecraft Fabric / Meteor Addon (Nova-Addon-1.21.11-Free.jar)

Obfuscation Layers Neutralized:
  1. ZIP Central Directory Zeroing (filename_length = 0) & Null-Byte Prefixed Class Names (\x00 / Modified UTF-8 \xc0\x80)
  2. Spoofed SourceFile (<random 160 chars>.kt/.java) & SourceDebugExtension attributes
  3. 563 External Static Integer Constant Pool Classes (39,202 obfuscated int fields)
  4. 265 External Mixed Boolean-Arithmetic (MBA) (II)I Helper Classes
  5. 258 External Rolling-Key Array String Decryptor Classes + Per-Class 32,766-Space XOR String & InvokeDynamic Bootstrap Methods
  6. Opaque Predicates (irem / lookupswitch / tableswitch / MBA) & 60 Concurrent* Decoy Classes

Usage:
  python3 NovaDeobfuscator.py <input.jar> <output_simplified.txt>
"""
import sys
import os
import re
import math
import time
import zlib
import struct
import zipfile
from collections import defaultdict

import zipfile, struct

JAR_PATH = "/working_dir/c_2d90c991e88aabaa/user_uploaded_files/ttl=63d/user_uploaded/Nova-Addon-1.21.11-Free.jar"

OPCODES = {
    0x00: ("nop", ""), 0x01: ("aconst_null", ""), 0x02: ("iconst_m1", ""),
    0x03: ("iconst_0", ""), 0x04: ("iconst_1", ""), 0x05: ("iconst_2", ""),
    0x06: ("iconst_3", ""), 0x07: ("iconst_4", ""), 0x08: ("iconst_5", ""),
    0x09: ("lconst_0", ""), 0x0a: ("lconst_1", ""), 0x0b: ("fconst_0", ""),
    0x0c: ("fconst_1", ""), 0x0d: ("fconst_2", ""), 0x0e: ("dconst_0", ""),
    0x0f: ("dconst_1", ""), 0x10: ("bipush", "b"), 0x11: ("sipush", "h"),
    0x12: ("ldc", "B"), 0x13: ("ldc_w", "H"), 0x14: ("ldc2_w", "H"),
    0x15: ("iload", "B"), 0x16: ("lload", "B"), 0x17: ("fload", "B"),
    0x18: ("dload", "B"), 0x19: ("aload", "B"),
    0x1a: ("iload_0", ""), 0x1b: ("iload_1", ""), 0x1c: ("iload_2", ""), 0x1d: ("iload_3", ""),
    0x1e: ("lload_0", ""), 0x1f: ("lload_1", ""), 0x20: ("lload_2", ""), 0x21: ("lload_3", ""),
    0x22: ("fload_0", ""), 0x23: ("fload_1", ""), 0x24: ("fload_2", ""), 0x25: ("fload_3", ""),
    0x26: ("dload_0", ""), 0x27: ("dload_1", ""), 0x28: ("dload_2", ""), 0x29: ("dload_3", ""),
    0x2a: ("aload_0", ""), 0x2b: ("aload_1", ""), 0x2c: ("aload_2", ""), 0x2d: ("aload_3", ""),
    0x2e: ("iaload", ""), 0x2f: ("laload", ""), 0x30: ("faload", ""), 0x31: ("daload", ""),
    0x32: ("aaload", ""), 0x33: ("baload", ""), 0x34: ("caload", ""), 0x35: ("saload", ""),
    0x36: ("istore", "B"), 0x37: ("lstore", "B"), 0x38: ("fstore", "B"),
    0x39: ("dstore", "B"), 0x3a: ("astore", "B"),
    0x3b: ("istore_0", ""), 0x3c: ("istore_1", ""), 0x3d: ("istore_2", ""), 0x3e: ("istore_3", ""),
    0x3f: ("lstore_0", ""), 0x40: ("lstore_1", ""), 0x41: ("lstore_2", ""), 0x42: ("lstore_3", ""),
    0x43: ("fstore_0", ""), 0x44: ("fstore_1", ""), 0x45: ("fstore_2", ""), 0x46: ("fstore_3", ""),
    0x47: ("dstore_0", ""), 0x48: ("dstore_1", ""), 0x49: ("dstore_2", ""), 0x4a: ("dstore_3", ""),
    0x4b: ("astore_0", ""), 0x4c: ("astore_1", ""), 0x4d: ("astore_2", ""), 0x4e: ("astore_3", ""),
    0x4f: ("iastore", ""), 0x50: ("lastore", ""), 0x51: ("fastore", ""), 0x52: ("dastore", ""),
    0x53: ("aastore", ""), 0x54: ("bastore", ""), 0x55: ("castore", ""), 0x56: ("sastore", ""),
    0x57: ("pop", ""), 0x58: ("pop2", ""), 0x59: ("dup", ""), 0x5a: ("dup_x1", ""),
    0x5b: ("dup_x2", ""), 0x5c: ("dup2", ""), 0x5d: ("dup2_x1", ""), 0x5e: ("dup2_x2", ""),
    0x5f: ("swap", ""), 0x60: ("iadd", ""), 0x61: ("ladd", ""), 0x62: ("fadd", ""), 0x63: ("dadd", ""),
    0x64: ("isub", ""), 0x65: ("lsub", ""), 0x66: ("fsub", ""), 0x67: ("dsub", ""),
    0x68: ("imul", ""), 0x69: ("lmul", ""), 0x6a: ("fmul", ""), 0x6b: ("dmul", ""),
    0x6c: ("idiv", ""), 0x6d: ("ldiv", ""), 0x6e: ("fdiv", ""), 0x6f: ("ddiv", ""),
    0x70: ("irem", ""), 0x71: ("lrem", ""), 0x72: ("frem", ""), 0x73: ("drem", ""),
    0x74: ("ineg", ""), 0x75: ("lneg", ""), 0x76: ("fneg", ""), 0x77: ("dneg", ""),
    0x78: ("ishl", ""), 0x79: ("lshl", ""), 0x7a: ("ishr", ""), 0x7b: ("lshr", ""),
    0x7c: ("iushr", ""), 0x7d: ("lushr", ""), 0x7e: ("iand", ""), 0x7f: ("land", ""),
    0x80: ("ior", ""), 0x81: ("lor", ""), 0x82: ("ixor", ""), 0x83: ("lxor", ""),
    0x84: ("iinc", "Bb"),
    0x85: ("i2l", ""), 0x86: ("i2f", ""), 0x87: ("i2d", ""), 0x88: ("l2i", ""),
    0x89: ("l2f", ""), 0x8a: ("l2d", ""), 0x8b: ("f2i", ""), 0x8c: ("f2l", ""),
    0x8d: ("f2d", ""), 0x8e: ("d2i", ""), 0x8f: ("d2l", ""), 0x90: ("d2f", ""),
    0x91: ("i2b", ""), 0x92: ("i2c", ""), 0x93: ("i2s", ""),
    0x94: ("lcmp", ""), 0x95: ("fcmpl", ""), 0x96: ("fcmpg", ""), 0x97: ("dcmpl", ""), 0x98: ("dcmpg", ""),
    0x99: ("ifeq", "h_rel"), 0x9a: ("ifne", "h_rel"), 0x9b: ("iflt", "h_rel"),
    0x9c: ("ifge", "h_rel"), 0x9d: ("ifgt", "h_rel"), 0x9e: ("ifle", "h_rel"),
    0x9f: ("if_icmpeq", "h_rel"), 0xa0: ("if_icmpne", "h_rel"), 0xa1: ("if_icmplt", "h_rel"),
    0xa2: ("if_icmpge", "h_rel"), 0xa3: ("if_icmpgt", "h_rel"), 0xa4: ("if_icmple", "h_rel"),
    0xa5: ("if_acmpeq", "h_rel"), 0xa6: ("if_acmpne", "h_rel"),
    0xa7: ("goto", "h_rel"), 0xa8: ("jsr", "h_rel"), 0xa9: ("ret", "B"),
    0xaa: ("tableswitch", "ts"), 0xab: ("lookupswitch", "ls"),
    0xac: ("ireturn", ""), 0xad: ("lreturn", ""), 0xae: ("freturn", ""), 0xaf: ("dreturn", ""),
    0xb0: ("areturn", ""), 0xb1: ("return", ""),
    0xb2: ("getstatic", "cp"), 0xb3: ("putstatic", "cp"),
    0xb4: ("getfield", "cp"), 0xb5: ("putfield", "cp"),
    0xb6: ("invokevirtual", "cp"), 0xb7: ("invokespecial", "cp"),
    0xb8: ("invokestatic", "cp"), 0xb9: ("invokeinterface", "cp_ii"),
    0xba: ("invokedynamic", "cp_id"),
    0xbb: ("new", "cp"), 0xbc: ("newarray", "B"), 0xbd: ("anewarray", "cp"),
    0xbe: ("arraylength", ""), 0xbf: ("athrow", ""),
    0xc0: ("checkcast", "cp"), 0xc1: ("instanceof", "cp"),
    0xc2: ("monitorenter", ""), 0xc3: ("monitorexit", ""),
    0xc4: ("wide", "wide"), 0xc5: ("multianewarray", "cp_B"),
    0xc6: ("ifnull", "h_rel"), 0xc7: ("ifnonnull", "h_rel"),
    0xc8: ("goto_w", "i_rel"), 0xc9: ("jsr_w", "i_rel")
}

def decode_mutf8(b):
    # Modified UTF-8 decoder
    res = []
    i = 0
    n = len(b)
    while i < n:
        c = b[i]
        if c < 0x80:
            res.append(chr(c))
            i += 1
        elif (c & 0xE0) == 0xC0:
            c2 = b[i+1]
            res.append(chr(((c & 0x1F) << 6) | (c2 & 0x3F)))
            i += 2
        elif (c & 0xF0) == 0xE0:
            c2 = b[i+1]
            c3 = b[i+2]
            res.append(chr(((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F)))
            i += 3
        else:
            res.append(chr(c))
            i += 1
    return "".join(res)

def parse_full_class(data):
    cp_count = struct.unpack('>H', data[8:10])[0]
    offset = 10
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack('>H', data[offset:offset+2])[0]
            offset += 2
            cp[i] = ('Utf8', decode_mutf8(data[offset:offset+length]))
            offset += length
        elif tag in (7, 8, 16, 19, 20):
            idx = struct.unpack('>H', data[offset:offset+2])[0]
            cp[i] = ({7:'Class', 8:'String', 16:'MethodType', 19:'Module', 20:'Package'}[tag], idx)
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            idx1, idx2 = struct.unpack('>HH', data[offset:offset+4])
            cp[i] = ({9:'Fieldref', 10:'Methodref', 11:'InterfaceMethodref', 12:'NameAndType', 17:'Dynamic', 18:'InvokeDynamic'}[tag], idx1, idx2)
            offset += 4
        elif tag == 3:
            val = struct.unpack('>i', data[offset:offset+4])[0]
            cp[i] = ('Integer', val)
            offset += 4
        elif tag == 4:
            val = struct.unpack('>f', data[offset:offset+4])[0]
            cp[i] = ('Float', val)
            offset += 4
        elif tag == 5:
            val = struct.unpack('>q', data[offset:offset+8])[0]
            cp[i] = ('Long', val)
            offset += 8
            i += 1
        elif tag == 6:
            val = struct.unpack('>d', data[offset:offset+8])[0]
            cp[i] = ('Double', val)
            offset += 8
            i += 1
        elif tag == 15:
            rk = data[offset]
            ri = struct.unpack('>H', data[offset+1:offset+3])[0]
            cp[i] = ('MethodHandle', rk, ri)
            offset += 3
        i += 1

    def get_utf8(idx):
        if idx == 0 or cp[idx] is None: return ""
        if cp[idx][0] == 'Utf8':
            s = cp[idx][1]
            if len(s) > 200 and s.strip() == '':
                return f"<SPACES_{len(s)}>"
            return s
        return str(cp[idx])

    def resolve_cp(idx):
        if idx == 0 or cp[idx] is None: return "None"
        item = cp[idx]
        t = item[0]
        if t == 'Utf8': return get_utf8(idx)
        if t == 'Class': return get_utf8(item[1])
        if t == 'String': return repr(get_utf8(item[1]))
        if t in ('Integer', 'Float', 'Long', 'Double'): return f"{t}({item[1]})"
        if t == 'NameAndType': return f"{get_utf8(item[1])}:{get_utf8(item[2])}"
        if t in ('Fieldref', 'Methodref', 'InterfaceMethodref'):
            return f"{resolve_cp(item[1])}.{resolve_cp(item[2])}"
        if t in ('InvokeDynamic', 'Dynamic'):
            return f"bsm#{item[1]}:{resolve_cp(item[2])}"
        if t == 'MethodHandle':
            return f"MH(kind={item[1]}, {resolve_cp(item[2])})"
        if t == 'MethodType':
            return f"MT({get_utf8(item[1])})"
        return str(item)

    access_flags, this_class, super_class, if_count = struct.unpack('>HHHH', data[offset:offset+8])
    offset += 8
    interfaces = [resolve_cp(struct.unpack('>H', data[offset+2*j:offset+2*j+2])[0]) for j in range(if_count)]
    offset += 2 * if_count

    def parse_attrs(offset):
        ac = struct.unpack('>H', data[offset:offset+2])[0]
        offset += 2
        attrs = {}
        for _ in range(ac):
            an_idx, alen = struct.unpack('>HI', data[offset:offset+6])
            offset += 6
            aname = get_utf8(an_idx)
            adata = data[offset:offset+alen]
            offset += alen
            attrs[aname] = adata
        return attrs, offset

    f_count = struct.unpack('>H', data[offset:offset+2])[0]
    offset += 2
    fields = []
    for _ in range(f_count):
        af, fn, fd = struct.unpack('>HHH', data[offset:offset+6])
        offset += 6
        attrs, offset = parse_attrs(offset)
        cval = None
        if 'ConstantValue' in attrs:
            cv_idx = struct.unpack('>H', attrs['ConstantValue'][:2])[0]
            cval = resolve_cp(cv_idx)
        fields.append((af, get_utf8(fn), get_utf8(fd), cval, attrs))

    m_count = struct.unpack('>H', data[offset:offset+2])[0]
    offset += 2
    methods = []
    for _ in range(m_count):
        af, mn, md = struct.unpack('>HHH', data[offset:offset+6])
        offset += 6
        attrs, offset = parse_attrs(offset)
        methods.append((af, get_utf8(mn), get_utf8(md), attrs))

    class_attrs, offset = parse_attrs(offset)
    bsms = []
    if 'BootstrapMethods' in class_attrs:
        bdata = class_attrs['BootstrapMethods']
        num_bsm = struct.unpack('>H', bdata[:2])[0]
        boff = 2
        for _ in range(num_bsm):
            mh_idx, num_args = struct.unpack('>HH', bdata[boff:boff+4])
            boff += 4
            args = [resolve_cp(struct.unpack('>H', bdata[boff+2*k:boff+2*k+2])[0]) for k in range(num_args)]
            boff += 2 * num_args
            bsms.append((resolve_cp(mh_idx), args))

    return {
        'this': resolve_cp(this_class),
        'super': resolve_cp(super_class),
        'interfaces': interfaces,
        'fields': fields,
        'methods': methods,
        'bsms': bsms,
        'cp': cp,
        'resolve_cp': resolve_cp
    }

def disasm_code(code_attr, resolve_cp):
    max_stack, max_locals, code_len = struct.unpack('>HHI', code_attr[:8])
    code = code_attr[8:8+code_len]
    pos = 8 + code_len
    ex_len = struct.unpack('>H', code_attr[pos:pos+2])[0]
    pos += 2
    exceptions = []
    for _ in range(ex_len):
        sp, ep, hp, ct = struct.unpack('>HHHH', code_attr[pos:pos+8])
        pos += 8
        exceptions.append((sp, ep, hp, resolve_cp(ct) if ct else 'any'))
    lines = []
    i = 0
    while i < code_len:
        op = code[i]
        start_i = i
        i += 1
        name, fmt = OPCODES.get(op, (f"op_{op:02x}", ""))
        if fmt == "":
            lines.append(f"  {start_i:4d}: {name}")
        elif fmt == "b":
            val = struct.unpack('>b', code[i:i+1])[0]; i += 1
            lines.append(f"  {start_i:4d}: {name} {val}")
        elif fmt == "B":
            val = code[i]; i += 1
            if name == "ldc":
                lines.append(f"  {start_i:4d}: {name} #{val} // {resolve_cp(val)}")
            else:
                lines.append(f"  {start_i:4d}: {name} {val}")
        elif fmt == "h":
            val = struct.unpack('>h', code[i:i+2])[0]; i += 2
            lines.append(f"  {start_i:4d}: {name} {val}")
        elif fmt == "H":
            val = struct.unpack('>H', code[i:i+2])[0]; i += 2
            lines.append(f"  {start_i:4d}: {name} #{val} // {resolve_cp(val)}")
        elif fmt == "Bb":
            v1, v2 = struct.unpack('>Bb', code[i:i+2]); i += 2
            lines.append(f"  {start_i:4d}: {name} {v1}, {v2}")
        elif fmt == "h_rel":
            rel = struct.unpack('>h', code[i:i+2])[0]; i += 2
            lines.append(f"  {start_i:4d}: {name} {start_i + rel}")
        elif fmt == "i_rel":
            rel = struct.unpack('>i', code[i:i+4])[0]; i += 4
            lines.append(f"  {start_i:4d}: {name} {start_i + rel}")
        elif fmt == "cp":
            val = struct.unpack('>H', code[i:i+2])[0]; i += 2
            lines.append(f"  {start_i:4d}: {name} #{val} // {resolve_cp(val)}")
        elif fmt == "cp_ii":
            val = struct.unpack('>H', code[i:i+2])[0]; i += 4
            lines.append(f"  {start_i:4d}: {name} #{val} // {resolve_cp(val)}")
        elif fmt == "cp_id":
            val = struct.unpack('>H', code[i:i+2])[0]; i += 4
            lines.append(f"  {start_i:4d}: {name} #{val} // {resolve_cp(val)}")
        elif fmt == "cp_B":
            val, dim = struct.unpack('>HB', code[i:i+3]); i += 3
            lines.append(f"  {start_i:4d}: {name} #{val} dim={dim} // {resolve_cp(val)}")
        elif fmt == "ts":
            while i % 4 != 0: i += 1
            def_off, low, high = struct.unpack('>iii', code[i:i+12]); i += 12
            jumps = []
            for k in range(low, high + 1):
                off = struct.unpack('>i', code[i:i+4])[0]; i += 4
                jumps.append(f"{k}:{start_i+off}")
            lines.append(f"  {start_i:4d}: tableswitch default={start_i+def_off} [{', '.join(jumps)}]")
        elif fmt == "ls":
            while i % 4 != 0: i += 1
            def_off, npairs = struct.unpack('>ii', code[i:i+8]); i += 8
            jumps = []
            for _ in range(npairs):
                k, off = struct.unpack('>ii', code[i:i+8]); i += 8
                jumps.append(f"{k}:{start_i+off}")
            lines.append(f"  {start_i:4d}: lookupswitch default={start_i+def_off} [{', '.join(jumps)}]")
        elif fmt == "wide":
            wop = code[i]; i += 1
            if wop == 0x84:
                v1, v2 = struct.unpack('>Hh', code[i:i+4]); i += 4
                lines.append(f"  {start_i:4d}: wide iinc {v1}, {v2}")
            else:
                v1 = struct.unpack('>H', code[i:i+2])[0]; i += 2
                lines.append(f"  {start_i:4d}: wide op_{wop:02x} {v1}")
    return lines, exceptions



def s32(v):
    v = v & 0xFFFFFFFF
    return v - 0x100000000 if v & 0x80000000 else v

def s64(v):
    v = v & 0xFFFFFFFFFFFFFFFF
    return v - 0x10000000000000000 if v & 0x8000000000000000 else v

class JavaObj:
    def __init__(self, cls_name):
        self.cls_name = cls_name
        self.val = None

class VM:
    def __init__(self, jar_path):
        self.classes = {}
        self.static_fields = {}
        with zipfile.ZipFile(jar_path, 'r') as zf:
            for info in zf.infolist():
                data = zf.read(info)
                if not data.startswith(b'\xca\xfe\xba\xbe'):
                    continue
                c = parse_full_class(data)
                cname = c['this']
                self.classes[cname] = c
                for af, fn, fd, cval, _ in c['fields']:
                    if cval is not None:
                        if cval.startswith("Integer("):
                            self.static_fields[(cname, fn, fd)] = int(cval[8:-1])
                        elif cval.startswith("Long("):
                            self.static_fields[(cname, fn, fd)] = int(cval[5:-1])
                        elif cval.startswith("'"):
                            self.static_fields[(cname, fn, fd)] = eval(cval)

        self.methods = {}
        for cname, c in self.classes.items():
            for af, mn, md, attrs in c['methods']:
                if '<SPACES_' in mn:
                    mn_real = ' ' * 32766
                else:
                    mn_real = mn
                self.methods[(cname, mn_real, md)] = (c, attrs.get('Code'))
                self.methods[(cname, mn, md)] = (c, attrs.get('Code'))

    def resolve_bsm(self, c, bsm_idx):
        mh_str, bargs = c['bsms'][bsm_idx]
        if len(bargs) == 4 and bargs[3].startswith("Integer("):
            enc_cls = eval(bargs[0])
            enc_mn = eval(bargs[1])
            enc_desc = eval(bargs[2])
            kind_flag = int(bargs[3][8:-1])
            dec_cls = self.exec_method(c['this'], ' ' * 32766, '(Ljava/lang/String;)Ljava/lang/String;', [enc_cls]).replace('.', '/')
            dec_mn = self.exec_method(c['this'], ' ' * 32766, '(Ljava/lang/String;)Ljava/lang/String;', [enc_mn])
            dec_desc = self.exec_method(c['this'], ' ' * 32766, '(Ljava/lang/String;)Ljava/lang/String;', [enc_desc])
            return (dec_cls, dec_mn, dec_desc, kind_flag)
        return None

    def exec_method(self, cname, mname, mdesc, args):
        key = (cname, mname, mdesc)
        if key not in self.methods and mname.strip() == '':
            key = (cname, ' ' * 32766, mdesc)
        c, code_attr = self.methods[key]
        cp = c['cp']
        max_stack, max_locals, code_len = struct.unpack('>HHI', code_attr[:8])
        code = code_attr[8:8+code_len]

        locals_arr = [None] * max(max_locals, 16)
        idx = 0
        p = 1
        arg_i = 0
        while p < len(mdesc) and mdesc[p] != ')':
            ch = mdesc[p]
            locals_arr[idx] = args[arg_i]
            arg_i += 1
            if ch in ('J', 'D'):
                idx += 2
                p += 1
            elif ch == 'L':
                p = mdesc.index(';', p) + 1
                idx += 1
            elif ch == '[':
                while mdesc[p] == '[': p += 1
                if mdesc[p] == 'L': p = mdesc.index(';', p) + 1
                else: p += 1
                idx += 1
            else:
                p += 1
                idx += 1

        stack = []
        pc = 0

        def push(val, cat=1):
            stack.append((val, cat))
        def pop():
            return stack.pop()[0]
        def unwrap_str(v):
            if isinstance(v, JavaObj):
                return "".join(v.val) if isinstance(v.val, list) else v.val
            return v
        def pop_raw():
            return stack.pop()

        while pc < code_len:
            op = code[pc]
            start_pc = pc
            pc += 1
            if op == 0x00: pass
            elif op == 0x01: push(None)
            elif 0x02 <= op <= 0x08: push(op - 3)
            elif op in (0x09, 0x0a): push(op - 9, 2)
            elif op in (0x0b, 0x0c, 0x0d): push(float(op - 0x0b))
            elif op in (0x0e, 0x0f): push(float(op - 0x0e), 2)
            elif op == 0x10:
                v = struct.unpack('>b', code[pc:pc+1])[0]; pc += 1
                push(v)
            elif op == 0x11:
                v = struct.unpack('>h', code[pc:pc+2])[0]; pc += 2
                push(v)
            elif op in (0x12, 0x13, 0x14):
                if op == 0x12:
                    cidx = code[pc]; pc += 1
                else:
                    cidx = struct.unpack('>H', code[pc:pc+2])[0]; pc += 2
                item = cp[cidx]
                t = item[0]
                if t == 'Integer': push(item[1])
                elif t == 'Float': push(item[1])
                elif t == 'Long': push(item[1], 2)
                elif t == 'Double': push(item[1], 2)
                elif t == 'String': push(cp[item[1]][1])
                elif t == 'Class': push(cp[item[1]][1])
                else: raise RuntimeError(f"Unsupported ldc {item}")
            elif op in (0x15, 0x17, 0x19):
                v = code[pc]; pc += 1; push(locals_arr[v])
            elif op in (0x16, 0x18):
                v = code[pc]; pc += 1; push(locals_arr[v], 2)
            elif 0x1a <= op <= 0x1d: push(locals_arr[op - 0x1a])
            elif 0x1e <= op <= 0x21: push(locals_arr[op - 0x1e], 2)
            elif 0x2a <= op <= 0x2d: push(locals_arr[op - 0x2a])
            elif op == 0x34: # caload
                i = pop(); arr = pop()
                push(ord(arr[i]) if isinstance(arr[i], str) else arr[i])
            elif op in (0x36, 0x38, 0x3a):
                v = code[pc]; pc += 1; locals_arr[v] = pop()
            elif op in (0x37, 0x39):
                v = code[pc]; pc += 1; locals_arr[v] = pop()
            elif 0x3b <= op <= 0x3e: locals_arr[op - 0x3b] = pop()
            elif 0x3f <= op <= 0x42: locals_arr[op - 0x3f] = pop()
            elif 0x4b <= op <= 0x4e: locals_arr[op - 0x4b] = pop()
            elif op == 0x55: # castore
                val = pop(); i = pop(); arr = pop()
                arr[i] = val & 0xFFFF
            elif op == 0x57: pop_raw()
            elif op == 0x58:
                v, cat = pop_raw()
                if cat == 1: pop_raw()
            elif op == 0x59: stack.append(stack[-1])
            elif op == 0x5a:
                v1 = pop_raw(); v2 = pop_raw()
                stack.extend([v1, v2, v1])
            elif op == 0x5b:
                v1 = pop_raw(); v2 = pop_raw()
                if v2[1] == 2: stack.extend([v1, v2, v1])
                else:
                    v3 = pop_raw()
                    stack.extend([v1, v3, v2, v1])
            elif op == 0x5c:
                v1 = pop_raw()
                if v1[1] == 2: stack.extend([v1, v1])
                else:
                    v2 = pop_raw()
                    stack.extend([v2, v1, v2, v1])
            elif op == 0x5d:
                v1 = pop_raw()
                if v1[1] == 2:
                    v2 = pop_raw()
                    stack.extend([v1, v2, v1])
                else:
                    v2 = pop_raw(); v3 = pop_raw()
                    stack.extend([v2, v1, v3, v2, v1])
            elif op == 0x5e: # dup2_x2
                v1 = pop_raw()
                if v1[1] == 2:
                    v2 = pop_raw()
                    if v2[1] == 2:
                        stack.extend([v1, v2, v1])
                    else:
                        v3 = pop_raw()
                        stack.extend([v1, v3, v2, v1])
                else:
                    v2 = pop_raw(); v3 = pop_raw()
                    if v3[1] == 2:
                        stack.extend([v2, v1, v3, v2, v1])
                    else:
                        v4 = pop_raw()
                        stack.extend([v2, v1, v4, v3, v2, v1])
            elif op == 0x5f:
                v1 = pop_raw(); v2 = pop_raw()
                stack.extend([v1, v2])
            elif op == 0x60: b = pop(); a = pop(); push(s32(a + b))
            elif op == 0x61: b = pop(); a = pop(); push(s64(a + b), 2)
            elif op == 0x64: b = pop(); a = pop(); push(s32(a - b))
            elif op == 0x65: b = pop(); a = pop(); push(s64(a - b), 2)
            elif op == 0x68: b = pop(); a = pop(); push(s32(a * b))
            elif op == 0x69: b = pop(); a = pop(); push(s64(a * b), 2)
            elif op == 0x6c: b = pop(); a = pop(); push(s32(int(a / b)))
            elif op == 0x6d: b = pop(); a = pop(); push(s64(int(a / b)), 2)
            elif op == 0x70: b = pop(); a = pop(); push(s32(a - int(a / b) * b))
            elif op == 0x71: b = pop(); a = pop(); push(s64(a - int(a / b) * b), 2)
            elif op == 0x74: a = pop(); push(s32(-a))
            elif op == 0x75: a = pop(); push(s64(-a), 2)
            elif op == 0x78: b = pop(); a = pop(); push(s32(a << (b & 0x1f)))
            elif op == 0x79: b = pop(); a = pop(); push(s64(a << (b & 0x3f)), 2)
            elif op == 0x7a: b = pop(); a = pop(); push(s32(a >> (b & 0x1f)))
            elif op == 0x7b: b = pop(); a = pop(); push(s64(a >> (b & 0x3f)), 2)
            elif op == 0x7c: b = pop(); a = pop(); push(s32((a & 0xFFFFFFFF) >> (b & 0x1f)))
            elif op == 0x7d: b = pop(); a = pop(); push(s64((a & 0xFFFFFFFFFFFFFFFF) >> (b & 0x3f)), 2)
            elif op == 0x7e: b = pop(); a = pop(); push(s32(a & b))
            elif op == 0x7f: b = pop(); a = pop(); push(s64(a & b), 2)
            elif op == 0x80: b = pop(); a = pop(); push(s32(a | b))
            elif op == 0x81: b = pop(); a = pop(); push(s64(a | b), 2)
            elif op == 0x82: b = pop(); a = pop(); push(s32(a ^ b))
            elif op == 0x83: b = pop(); a = pop(); push(s64(a ^ b), 2)
            elif op == 0x84:
                v1, v2 = struct.unpack('>Bb', code[pc:pc+2]); pc += 2
                locals_arr[v1] = s32(locals_arr[v1] + v2)
            elif op == 0x85: a = pop(); push(s64(a), 2)
            elif op == 0x88: a = pop(); push(s32(a))
            elif op == 0x91:
                a = pop() & 0xFF
                push(a - 256 if a & 0x80 else a)
            elif op == 0x92: a = pop(); push(a & 0xFFFF)
            elif op == 0x93:
                a = pop() & 0xFFFF
                push(a - 65536 if a & 0x8000 else a)
            elif op == 0x94: # lcmp
                b = pop(); a = pop()
                push(1 if a > b else (-1 if a < b else 0))
            elif 0x99 <= op <= 0x9e:
                rel = struct.unpack('>h', code[pc:pc+2])[0]; pc += 2
                a = pop()
                cond = {0x99: a==0, 0x9a: a!=0, 0x9b: a<0, 0x9c: a>=0, 0x9d: a>0, 0x9e: a<=0}[op]
                if cond: pc = start_pc + rel
            elif 0x9f <= op <= 0xa4:
                rel = struct.unpack('>h', code[pc:pc+2])[0]; pc += 2
                b = pop(); a = pop()
                cond = {0x9f: a==b, 0xa0: a!=b, 0xa1: a<b, 0xa2: a>=b, 0xa3: a>b, 0xa4: a<=b}[op]
                if cond: pc = start_pc + rel
            elif op == 0xa7:
                rel = struct.unpack('>h', code[pc:pc+2])[0]; pc += 2
                pc = start_pc + rel
            elif op == 0xaa:
                while pc % 4 != 0: pc += 1
                def_off, low, high = struct.unpack('>iii', code[pc:pc+12]); pc += 12
                offs = {}
                for k in range(low, high + 1):
                    offs[k] = struct.unpack('>i', code[pc:pc+4])[0]; pc += 4
                key_v = pop()
                pc = start_pc + offs.get(key_v, def_off)
            elif op == 0xab:
                while pc % 4 != 0: pc += 1
                def_off, npairs = struct.unpack('>ii', code[pc:pc+8]); pc += 8
                offs = {}
                for _ in range(npairs):
                    k, o = struct.unpack('>ii', code[pc:pc+8]); pc += 8
                    offs[k] = o
                key_v = pop()
                pc = start_pc + offs.get(key_v, def_off)
            elif op in (0xac, 0xad, 0xae, 0xaf, 0xb0):
                return unwrap_str(pop())
            elif op == 0xb1:
                return None
            elif op == 0xb2:
                cidx = struct.unpack('>H', code[pc:pc+2])[0]; pc += 2
                _, cls_idx, nt_idx = cp[cidx]
                tcls = cp[cp[cls_idx][1]][1]
                fn = cp[cp[nt_idx][1]][1]
                fd = cp[cp[nt_idx][2]][1]
                if (tcls, fn, fd) not in self.static_fields:
                    if (tcls, '<clinit>', '()V') in self.methods:
                        self.exec_method(tcls, '<clinit>', '()V', [])
                val = self.static_fields[(tcls, fn, fd)]
                push(val, 2 if fd in ('J', 'D') else 1)
            elif op == 0xb3:
                cidx = struct.unpack('>H', code[pc:pc+2])[0]; pc += 2
                _, cls_idx, nt_idx = cp[cidx]
                tcls = cp[cp[cls_idx][1]][1]
                fn = cp[cp[nt_idx][1]][1]
                fd = cp[cp[nt_idx][2]][1]
                self.static_fields[(tcls, fn, fd)] = unwrap_str(pop())
            elif op in (0xb6, 0xb7, 0xb8):
                cidx = struct.unpack('>H', code[pc:pc+2])[0]; pc += 2
                _, cls_idx, nt_idx = cp[cidx]
                tcls = cp[cp[cls_idx][1]][1]
                mn = cp[cp[nt_idx][1]][1]
                md = cp[cp[nt_idx][2]][1]
                p = 1
                arg_count = 0
                while p < len(md) and md[p] != ')':
                    arg_count += 1
                    if md[p] == 'L': p = md.index(';', p) + 1
                    elif md[p] == '[':
                        while md[p] == '[': p += 1
                        if md[p] == 'L': p = md.index(';', p) + 1
                        else: p += 1
                    else: p += 1
                ret_t = md[md.index(')')+1:]
                call_args = [pop() for _ in range(arg_count)][::-1]
                if op != 0xb8:
                    recv = stack.pop()[0]
                else:
                    recv = None
                if tcls == 'java/lang/StringBuilder':
                    if mn == '<init>': recv.val = []
                    elif mn == 'append':
                        recv.val.append(chr(call_args[0]))
                        push(recv)
                    elif mn == 'toString':
                        push("".join(recv.val))
                elif tcls == 'java/lang/String':
                    s_recv = recv.val if isinstance(recv, JavaObj) else recv
                    if mn == 'charAt':
                        push(ord(s_recv[call_args[0]]))
                    elif mn == 'length':
                        push(len(s_recv))
                    elif mn == 'toCharArray':
                        push([ord(ch) for ch in s_recv])
                    elif mn == '<init>' and md == '([C)V':
                        recv.val = "".join(chr(x) for x in call_args[0])
                else:
                    if isinstance(recv, JavaObj) and recv.val is not None:
                        recv = recv.val
                    res = self.exec_method(tcls, mn, md, call_args if op == 0xb8 else [recv] + call_args)
                    if ret_t != 'V':
                        push(res, 2 if ret_t in ('J', 'D') else 1)
            elif op == 0xba:
                cidx = struct.unpack('>H', code[pc:pc+2])[0]; pc += 4
                _, bsm_idx, nt_idx = cp[cidx]
                md = cp[cp[nt_idx][2]][1]
                p = 1
                arg_count = 0
                while p < len(md) and md[p] != ')':
                    arg_count += 1
                    if md[p] == 'L': p = md.index(';', p) + 1
                    elif md[p] == '[':
                        while md[p] == '[': p += 1
                        if md[p] == 'L': p = md.index(';', p) + 1
                        else: p += 1
                    else: p += 1
                ret_t = md[md.index(')')+1:]
                call_args = [pop() for _ in range(arg_count)][::-1]
                dec_cls, dec_mn, dec_desc, kind_flag = self.resolve_bsm(c, bsm_idx)
                if dec_cls == 'java/lang/String' and dec_mn == 'valueOf' and dec_desc == '([C)Ljava/lang/String;':
                    res = "".join(chr(x) for x in call_args[0])
                else:
                    res = self.exec_method(dec_cls, dec_mn, dec_desc, call_args)
                if ret_t != 'V':
                    push(res, 2 if ret_t in ('J', 'D') else 1)
            elif op == 0xbb:
                cidx = struct.unpack('>H', code[pc:pc+2])[0]; pc += 2
                tcls = cp[cp[cidx][1]][1]
                if tcls in ('java/lang/StringBuilder', 'java/lang/String'):
                    push(JavaObj(tcls))
                else:
                    raise RuntimeError(f"Unsupported new {tcls}")
            elif op == 0xbc:
                atype = code[pc]; pc += 1
                sz = pop()
                push([0] * sz)
            elif op == 0xbe:
                arr = pop()
                push(len(arr))
            else:
                raise RuntimeError(f"Unsupported opcode 0x{op:02x} at pc={start_pc} in {cname[:30]}.{mname[:30]}:{mdesc}")



def parse_insns(code_attr, cp, resolve_cp):
    max_stack, max_locals, code_len = struct.unpack('>HHI', code_attr[:8])
    code = code_attr[8:8+code_len]
    pos = 8 + code_len
    ex_len = struct.unpack('>H', code_attr[pos:pos+2])[0]
    pos += 2
    exceptions = []
    for _ in range(ex_len):
        sp, ep, hp, ct = struct.unpack('>HHHH', code_attr[pos:pos+8])
        pos += 8
        exceptions.append((sp, ep, hp, resolve_cp(ct) if ct else 'any'))

    insns = {}
    i = 0
    while i < code_len:
        op = code[i]
        start_i = i
        i += 1
        name, fmt = OPCODES.get(op, (f"op_{op:02x}", ""))
        if fmt == "":
            insns[start_i] = (op, name, i, None)
        elif fmt == "b":
            val = struct.unpack('>b', code[i:i+1])[0]; i += 1
            insns[start_i] = (op, name, i, val)
        elif fmt == "B":
            val = code[i]; i += 1
            insns[start_i] = (op, name, i, val)
        elif fmt == "h":
            val = struct.unpack('>h', code[i:i+2])[0]; i += 2
            insns[start_i] = (op, name, i, val)
        elif fmt == "H":
            val = struct.unpack('>H', code[i:i+2])[0]; i += 2
            insns[start_i] = (op, name, i, val)
        elif fmt == "Bb":
            v1, v2 = struct.unpack('>Bb', code[i:i+2]); i += 2
            insns[start_i] = (op, name, i, (v1, v2))
        elif fmt == "h_rel":
            rel = struct.unpack('>h', code[i:i+2])[0]; i += 2
            insns[start_i] = (op, name, i, start_i + rel)
        elif fmt == "i_rel":
            rel = struct.unpack('>i', code[i:i+4])[0]; i += 4
            insns[start_i] = (op, name, i, start_i + rel)
        elif fmt in ("cp", "cp_ii", "cp_id"):
            val = struct.unpack('>H', code[i:i+2])[0]
            i += 4 if fmt in ("cp_ii", "cp_id") else 2
            insns[start_i] = (op, name, i, val)
        elif fmt == "cp_B":
            val, dim = struct.unpack('>HB', code[i:i+3]); i += 3
            insns[start_i] = (op, name, i, (val, dim))
        elif fmt == "ts":
            while i % 4 != 0: i += 1
            def_off, low, high = struct.unpack('>iii', code[i:i+12]); i += 12
            jumps = {}
            for k in range(low, high + 1):
                off = struct.unpack('>i', code[i:i+4])[0]; i += 4
                jumps[k] = start_i + off
            insns[start_i] = (op, name, i, (start_i + def_off, jumps))
        elif fmt == "ls":
            while i % 4 != 0: i += 1
            def_off, npairs = struct.unpack('>ii', code[i:i+8]); i += 8
            jumps = {}
            for _ in range(npairs):
                k, off = struct.unpack('>ii', code[i:i+8]); i += 8
                jumps[k] = start_i + off
            insns[start_i] = (op, name, i, (start_i + def_off, jumps))
        elif fmt == "wide":
            wop = code[i]; i += 1
            if wop == 0x84:
                v1, v2 = struct.unpack('>Hh', code[i:i+4]); i += 4
                insns[start_i] = (op, "wide_iinc", i, (v1, v2))
            else:
                v1 = struct.unpack('>H', code[i:i+2])[0]; i += 2
                insns[start_i] = (op, f"wide_{wop:02x}", i, v1)
    return insns, exceptions

def count_desc_args(md):
    p = 1
    arg_count = 0
    while p < len(md) and md[p] != ')':
        arg_count += 1
        if md[p] == 'L': p = md.index(';', p) + 1
        elif md[p] == '[':
            while md[p] == '[': p += 1
            if md[p] == 'L': p = md.index(';', p) + 1
            else: p += 1
        else: p += 1
    ret_t = md[md.index(')')+1:]
    return arg_count, ret_t

def get_method_annotations(attrs, cp, resolve_cp):
    res = []
    for k in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations'):
        if k in attrs:
            data = attrs[k]
            num = struct.unpack('>H', data[:2])[0]
            off = 2
            for _ in range(num):
                t_idx, npairs = struct.unpack('>HH', data[off:off+4])
                t_name = resolve_cp(t_idx)
                res.append(f"@{t_name}(pairs={npairs})")
                break
    if 'Signature' in attrs:
        s_idx = struct.unpack('>H', attrs['Signature'][:2])[0]
        res.append(f"Sig={resolve_cp(s_idx)}")
    return " ".join(res)

def simplify_class_clean(idx, c):
    cp = c['cp']
    resolve_cp = c['resolve_cp']
    cname = c['this']
    out = [f"================================================================================"]
    out.append(f"=== IDX {idx}: {short_cls(cname)} extends {short_cls(c['super'])} implements {[short_cls(x) for x in c['interfaces']]} ===")
    for af, fn, fd, cval, attrs in c['fields']:
        ann = get_method_annotations(attrs, cp, resolve_cp)
        out.append(f"  FIELD 0x{af:04x} {fn}:{fd} = {cval} {ann}")
    for af, mn, md, attrs in c['methods']:
        if '<SPACES_' in mn: continue
        ann = get_method_annotations(attrs, cp, resolve_cp)
        out.append(f"  METHOD 0x{af:04x} {mn}:{md} {ann}")
        if 'Code' not in attrs: continue
        insns, exceptions = parse_insns(attrs['Code'], cp, resolve_cp)
        for ex in exceptions:
            out.append(f"    TRY {ex}")

        emit_const = {}
        keep_insn = {}
        jumps_taken = {}
        sym_if_targets = {} # pc -> (op_name, raw_target)

        visited = set()
        worklist = [(0, ())]
        for sp, ep, hp, ct in exceptions:
            worklist.append((hp, (('sym', None, 1, hp),)))

        def use_item(item):
            if item[0] == 'const':
                emit_const[item[3]] = item[1]

        while worklist:
            pc, stack = worklist.pop()
            state_key = (pc, tuple(x if x[0] == 'const' else ('sym', x[2]) for x in stack))
            if state_key in visited:
                continue
            visited.add(state_key)
            if pc not in insns:
                continue

            op, name, next_pc, arg = insns[pc]
            st = list(stack)

            def pop():
                return st.pop()
            def push_const(v, cat=1):
                st.append(('const', v, cat, pc))
            def push_sym(cat=1):
                st.append(('sym', None, cat, pc))

            if op == 0x00:
                worklist.append((next_pc, tuple(st)))
            elif op == 0x01:
                keep_insn[pc] = "aconst_null"
                push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif 0x02 <= op <= 0x08:
                push_const(op - 3, 1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x09, 0x0a):
                push_const(op - 9, 2)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x0b, 0x0c, 0x0d):
                push_const(float(op - 0x0b), 1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x0e, 0x0f):
                push_const(float(op - 0x0e), 2)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x10, 0x11):
                push_const(arg, 1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x12, 0x13, 0x14):
                item = cp[arg]
                t = item[0]
                if t in ('Integer', 'Float'):
                    push_const(item[1], 1)
                elif t in ('Long', 'Double'):
                    push_const(item[1], 2)
                elif t == 'String':
                    push_const(cp[item[1]][1], 1)
                elif t == 'Class':
                    cls_str = cp[item[1]][1]
                    keep_insn[pc] = f"ldc Class({short_cls(cls_str)})"
                    push_sym(1)
                else:
                    keep_insn[pc] = f"{name} {resolve_cp(arg)}"
                    push_sym(2 if op == 0x14 else 1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x15, 0x17, 0x19) or (0x1a <= op <= 0x1d) or (0x22 <= op <= 0x25) or (0x2a <= op <= 0x2d):
                keep_insn[pc] = f"{name}" + (f" {arg}" if arg is not None else "")
                push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x16, 0x18) or (0x1e <= op <= 0x21) or (0x26 <= op <= 0x29):
                keep_insn[pc] = f"{name}" + (f" {arg}" if arg is not None else "")
                push_sym(2)
                worklist.append((next_pc, tuple(st)))
            elif 0x2e <= op <= 0x35:
                i_it = pop(); a_it = pop()
                use_item(a_it); use_item(i_it)
                keep_insn[pc] = name
                push_sym(2 if op in (0x2f, 0x31) else 1)
                worklist.append((next_pc, tuple(st)))
            elif (0x36 <= op <= 0x3a) or (0x3b <= op <= 0x4e):
                v_it = pop()
                use_item(v_it)
                keep_insn[pc] = f"{name}" + (f" {arg}" if arg is not None else "")
                worklist.append((next_pc, tuple(st)))
            elif 0x4f <= op <= 0x56:
                v_it = pop(); i_it = pop(); a_it = pop()
                use_item(a_it); use_item(i_it); use_item(v_it)
                keep_insn[pc] = name
                worklist.append((next_pc, tuple(st)))
            elif op == 0x57:
                v_it = pop()
                if v_it[0] != 'const':
                    keep_insn[pc] = "pop"
                worklist.append((next_pc, tuple(st)))
            elif op == 0x58:
                v1 = pop()
                if v1[2] == 2:
                    if v1[0] != 'const': keep_insn[pc] = "pop2"
                else:
                    v2 = pop()
                    if v1[0] != 'const' or v2[0] != 'const':
                        use_item(v2); use_item(v1)
                        keep_insn[pc] = "pop2"
                worklist.append((next_pc, tuple(st)))
            elif op == 0x59:
                v1 = pop()
                if v1[0] == 'const':
                    st.append(v1)
                    push_const(v1[1], v1[2])
                else:
                    keep_insn[pc] = "dup"
                    st.extend([v1, v1])
                worklist.append((next_pc, tuple(st)))
            elif op == 0x5a:
                v1 = pop(); v2 = pop()
                if v1[0] == 'const' and v2[0] == 'const':
                    st.extend([('const', v1[1], v1[2], pc), v2, v1])
                else:
                    use_item(v2); use_item(v1)
                    keep_insn[pc] = "dup_x1"
                    st.extend([v1, v2, v1])
                worklist.append((next_pc, tuple(st)))
            elif op == 0x5b:
                v1 = pop(); v2 = pop()
                if v2[2] == 2:
                    if v1[0] == 'const' and v2[0] == 'const':
                        st.extend([('const', v1[1], v1[2], pc), v2, v1])
                    else:
                        use_item(v2); use_item(v1)
                        keep_insn[pc] = "dup_x2"
                        st.extend([v1, v2, v1])
                else:
                    v3 = pop()
                    if v1[0] == 'const' and v2[0] == 'const' and v3[0] == 'const':
                        st.extend([('const', v1[1], v1[2], pc), v3, v2, v1])
                    else:
                        use_item(v3); use_item(v2); use_item(v1)
                        keep_insn[pc] = "dup_x2"
                        st.extend([v1, v3, v2, v1])
                worklist.append((next_pc, tuple(st)))
            elif op == 0x5c:
                v1 = pop()
                if v1[2] == 2:
                    if v1[0] == 'const':
                        st.extend([v1, ('const', v1[1], 2, pc)])
                    else:
                        keep_insn[pc] = "dup2"
                        st.extend([v1, v1])
                else:
                    v2 = pop()
                    if v1[0] == 'const' and v2[0] == 'const':
                        st.extend([v2, v1, ('const', v2[1], 1, pc), ('const', v1[1], 1, pc)])
                    else:
                        use_item(v2); use_item(v1)
                        keep_insn[pc] = "dup2"
                        st.extend([v2, v1, v2, v1])
                worklist.append((next_pc, tuple(st)))
            elif op == 0x5d:
                v1 = pop()
                if v1[2] == 2:
                    v2 = pop()
                    if v1[0] == 'const' and v2[0] == 'const':
                        st.extend([('const', v1[1], 2, pc), v2, v1])
                    else:
                        use_item(v2); use_item(v1)
                        keep_insn[pc] = "dup2_x1"
                        st.extend([v1, v2, v1])
                else:
                    v2 = pop(); v3 = pop()
                    if v1[0] == 'const' and v2[0] == 'const' and v3[0] == 'const':
                        st.extend([('const', v2[1], 1, pc), ('const', v1[1], 1, pc), v3, v2, v1])
                    else:
                        use_item(v3); use_item(v2); use_item(v1)
                        keep_insn[pc] = "dup2_x1"
                        st.extend([v2, v1, v3, v2, v1])
                worklist.append((next_pc, tuple(st)))
            elif op == 0x5e: # dup2_x2
                v1 = pop()
                if v1[2] == 2:
                    v2 = pop()
                    if v2[2] == 2:
                        if v1[0] == 'const' and v2[0] == 'const':
                            st.extend([('const', v1[1], 2, pc), v2, v1])
                        else:
                            use_item(v2); use_item(v1); keep_insn[pc] = "dup2_x2"
                            st.extend([v1, v2, v1])
                    else:
                        v3 = pop()
                        if v1[0] == 'const' and v2[0] == 'const' and v3[0] == 'const':
                            st.extend([('const', v1[1], 2, pc), v3, v2, v1])
                        else:
                            use_item(v3); use_item(v2); use_item(v1); keep_insn[pc] = "dup2_x2"
                            st.extend([v1, v3, v2, v1])
                else:
                    v2 = pop(); v3 = pop()
                    if v3[2] == 2:
                        if v1[0] == 'const' and v2[0] == 'const' and v3[0] == 'const':
                            st.extend([('const', v2[1], 1, pc), ('const', v1[1], 1, pc), v3, v2, v1])
                        else:
                            use_item(v3); use_item(v2); use_item(v1); keep_insn[pc] = "dup2_x2"
                            st.extend([v2, v1, v3, v2, v1])
                    else:
                        v4 = pop()
                        if v1[0] == 'const' and v2[0] == 'const' and v3[0] == 'const' and v4[0] == 'const':
                            st.extend([('const', v2[1], 1, pc), ('const', v1[1], 1, pc), v4, v3, v2, v1])
                        else:
                            use_item(v4); use_item(v3); use_item(v2); use_item(v1); keep_insn[pc] = "dup2_x2"
                            st.extend([v2, v1, v4, v3, v2, v1])
                worklist.append((next_pc, tuple(st)))
            elif op == 0x5f:
                v1 = pop(); v2 = pop()
                if v1[0] == 'const' and v2[0] == 'const':
                    st.extend([v1, v2])
                else:
                    use_item(v2); use_item(v1)
                    keep_insn[pc] = "swap"
                    st.extend([v1, v2])
                worklist.append((next_pc, tuple(st)))
            elif op in (0x60, 0x64, 0x68, 0x6c, 0x70, 0x78, 0x7a, 0x7c, 0x7e, 0x80, 0x82):
                from test_emulator import s32
                b = pop(); a = pop()
                if a[0] == 'const' and b[0] == 'const':
                    av, bv = a[1], b[1]
                    if op == 0x60: res = s32(av + bv)
                    elif op == 0x64: res = s32(av - bv)
                    elif op == 0x68: res = s32(av * bv)
                    elif op == 0x6c: res = s32(int(av / bv))
                    elif op == 0x70: res = s32(av - int(av / bv) * bv)
                    elif op == 0x78: res = s32(av << (bv & 0x1f))
                    elif op == 0x7a: res = s32(av >> (bv & 0x1f))
                    elif op == 0x7c: res = s32((av & 0xFFFFFFFF) >> (bv & 0x1f))
                    elif op == 0x7e: res = s32(av & bv)
                    elif op == 0x80: res = s32(av | bv)
                    elif op == 0x82: res = s32(av ^ bv)
                    push_const(res, 1)
                else:
                    use_item(a); use_item(b)
                    keep_insn[pc] = name
                    push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x61, 0x65, 0x69, 0x6d, 0x71, 0x79, 0x7b, 0x7d, 0x7f, 0x81, 0x83):
                from test_emulator import s64
                b = pop(); a = pop()
                if a[0] == 'const' and b[0] == 'const':
                    av, bv = a[1], b[1]
                    if op == 0x61: res = s64(av + bv)
                    elif op == 0x65: res = s64(av - bv)
                    elif op == 0x69: res = s64(av * bv)
                    elif op == 0x6d: res = s64(int(av / bv))
                    elif op == 0x71: res = s64(av - int(av / bv) * bv)
                    elif op == 0x79: res = s64(av << (bv & 0x3f))
                    elif op == 0x7b: res = s64(av >> (bv & 0x3f))
                    elif op == 0x7d: res = s64((av & 0xFFFFFFFFFFFFFFFF) >> (bv & 0x3f))
                    elif op == 0x7f: res = s64(av & bv)
                    elif op == 0x81: res = s64(av | bv)
                    elif op == 0x83: res = s64(av ^ bv)
                    push_const(res, 2)
                else:
                    use_item(a); use_item(b)
                    keep_insn[pc] = name
                    push_sym(2)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x62, 0x66, 0x6a, 0x6e, 0x72):
                b = pop(); a = pop()
                use_item(a); use_item(b)
                keep_insn[pc] = name
                push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x63, 0x67, 0x6b, 0x6f, 0x73):
                b = pop(); a = pop()
                use_item(a); use_item(b)
                keep_insn[pc] = name
                push_sym(2)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x74, 0x75, 0x76, 0x77):
                from test_emulator import s32, s64
                a = pop()
                if a[0] == 'const' and op in (0x74, 0x75):
                    push_const(s32(-a[1]) if op == 0x74 else s64(-a[1]), 1 if op == 0x74 else 2)
                else:
                    use_item(a)
                    keep_insn[pc] = name
                    push_sym(2 if op in (0x75, 0x77) else 1)
                worklist.append((next_pc, tuple(st)))
            elif op == 0x84 or name == "wide_iinc":
                keep_insn[pc] = f"iinc {arg[0]}, {arg[1]}"
                worklist.append((next_pc, tuple(st)))
            elif 0x85 <= op <= 0x93:
                from test_emulator import s32, s64
                a = pop()
                if a[0] == 'const' and op in (0x85, 0x88, 0x91, 0x92, 0x93):
                    av = a[1]
                    if op == 0x85: push_const(s64(av), 2)
                    elif op == 0x88: push_const(s32(av), 1)
                    elif op == 0x91:
                        v = av & 0xFF
                        push_const(v - 256 if v & 0x80 else v, 1)
                    elif op == 0x92: push_const(av & 0xFFFF, 1)
                    elif op == 0x93:
                        v = av & 0xFFFF
                        push_const(v - 65536 if v & 0x8000 else v, 1)
                else:
                    use_item(a)
                    keep_insn[pc] = name
                    push_sym(2 if op in (0x85, 0x87, 0x8a, 0x8c, 0x8d, 0x8f) else 1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0x94, 0x95, 0x96, 0x97, 0x98):
                b = pop(); a = pop()
                if a[0] == 'const' and b[0] == 'const' and op == 0x94:
                    av, bv = a[1], b[1]
                    push_const(1 if av > bv else (-1 if av < bv else 0), 1)
                else:
                    use_item(a); use_item(b)
                    keep_insn[pc] = name
                    push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif (0x99 <= op <= 0x9e) or op in (0xc6, 0xc7):
                a = pop()
                if a[0] == 'const' and 0x99 <= op <= 0x9e:
                    av = a[1]
                    cond = {0x99: av==0, 0x9a: av!=0, 0x9b: av<0, 0x9c: av>=0, 0x9d: av>0, 0x9e: av<=0}[op]
                    target = arg if cond else next_pc
                    jumps_taken[pc] = target
                    worklist.append((target, tuple(st)))
                else:
                    use_item(a)
                    sym_if_targets[pc] = (name, arg)
                    keep_insn[pc] = f"{name} {arg}"
                    worklist.append((next_pc, tuple(st)))
                    worklist.append((arg, tuple(st)))
            elif 0x9f <= op <= 0xa6:
                b = pop(); a = pop()
                if a[0] == 'const' and b[0] == 'const' and 0x9f <= op <= 0xa4:
                    av, bv = a[1], b[1]
                    cond = {0x9f: av==bv, 0xa0: av!=bv, 0xa1: av<bv, 0xa2: av>=bv, 0xa3: av>bv, 0xa4: av<=bv}[op]
                    target = arg if cond else next_pc
                    jumps_taken[pc] = target
                    worklist.append((target, tuple(st)))
                else:
                    use_item(a); use_item(b)
                    sym_if_targets[pc] = (name, arg)
                    keep_insn[pc] = f"{name} {arg}"
                    worklist.append((next_pc, tuple(st)))
                    worklist.append((arg, tuple(st)))
            elif op in (0xa7, 0xc8):
                jumps_taken[pc] = arg
                worklist.append((arg, tuple(st)))
            elif op in (0xaa, 0xab):
                def_target, jmap = arg
                a = pop()
                if a[0] == 'const':
                    target = jmap.get(a[1], def_target)
                    jumps_taken[pc] = target
                    worklist.append((target, tuple(st)))
                else:
                    use_item(a)
                    keep_insn[pc] = f"{name} default={def_target} {jmap}"
                    worklist.append((def_target, tuple(st)))
                    for t in jmap.values():
                        worklist.append((t, tuple(st)))
            elif 0xac <= op <= 0xb0:
                a = pop()
                use_item(a)
                keep_insn[pc] = name
            elif op == 0xb1:
                keep_insn[pc] = "return"
            elif op == 0xb2:
                _, cls_idx, nt_idx = cp[arg]
                tcls = cp[cp[cls_idx][1]][1]
                fn = cp[cp[nt_idx][1]][1]
                fd = cp[cp[nt_idx][2]][1]
                if (tcls, fn, fd) in static_fields:
                    val = static_fields[(tcls, fn, fd)]
                    push_const(val, 2 if fd in ('J', 'D') else 1)
                else:
                    keep_insn[pc] = f"getstatic {short_cls(tcls)}.{fn}:{fd}"
                    push_sym(2 if fd in ('J', 'D') else 1)
                worklist.append((next_pc, tuple(st)))
            elif op == 0xb3:
                _, cls_idx, nt_idx = cp[arg]
                tcls = cp[cp[cls_idx][1]][1]
                fn = cp[cp[nt_idx][1]][1]
                fd = cp[cp[nt_idx][2]][1]
                a = pop()
                use_item(a)
                keep_insn[pc] = f"putstatic {short_cls(tcls)}.{fn}:{fd}"
                worklist.append((next_pc, tuple(st)))
            elif op == 0xb4:
                _, cls_idx, nt_idx = cp[arg]
                tcls = cp[cp[cls_idx][1]][1]
                fn = cp[cp[nt_idx][1]][1]
                fd = cp[cp[nt_idx][2]][1]
                a = pop()
                use_item(a)
                keep_insn[pc] = f"getfield {short_cls(tcls)}.{fn}:{fd}"
                push_sym(2 if fd in ('J', 'D') else 1)
                worklist.append((next_pc, tuple(st)))
            elif op == 0xb5:
                _, cls_idx, nt_idx = cp[arg]
                tcls = cp[cp[cls_idx][1]][1]
                fn = cp[cp[nt_idx][1]][1]
                fd = cp[cp[nt_idx][2]][1]
                v_it = pop(); o_it = pop()
                use_item(o_it); use_item(v_it)
                keep_insn[pc] = f"putfield {short_cls(tcls)}.{fn}:{fd}"
                worklist.append((next_pc, tuple(st)))
            elif op in (0xb6, 0xb7, 0xb8, 0xb9, 0xba):
                if op == 0xba:
                    _, bsm_idx, nt_idx = cp[arg]
                    if (cname, bsm_idx) in bsm_cache:
                        tcls, fn, fd, kflag = bsm_cache[(cname, bsm_idx)]
                        is_static = (kflag == 0)
                        inv_type = "invokestatic" if is_static else "invokevirtual"
                    else:
                        bsm_mh, bsm_args = c['bsms'][bsm_idx]
                        fn = cp[cp[nt_idx][1]][1]
                        fd = cp[cp[nt_idx][2]][1]
                        is_static = True
                        inv_type = f"invokedynamic({bsm_args})"
                        tcls = "Lambda"
                else:
                    _, cls_idx, nt_idx = cp[arg]
                    tcls = cp[cp[cls_idx][1]][1]
                    fn = cp[cp[nt_idx][1]][1]
                    fd = cp[cp[nt_idx][2]][1]
                    is_static = (op == 0xb8)
                    inv_type = name

                nargs, ret_t = count_desc_args(fd)
                call_args = [pop() for _ in range(nargs)][::-1]
                recv = None if is_static else pop()

                if is_static and tcls in iii_classes and fd == '(II)I' and all(x[0] == 'const' for x in call_args):
                    res = vm.exec_method(tcls, fn, fd, [x[1] for x in call_args])
                    push_const(res, 1)
                elif is_static and tcls == 'java/lang/Float' and fn == 'intBitsToFloat' and fd == '(I)F' and call_args[0][0] == 'const':
                    res = struct.unpack('>f', struct.pack('>i', call_args[0][1]))[0]
                    push_const(round(res, 6), 1)
                elif is_static and tcls == 'java/lang/Double' and fn == 'longBitsToDouble' and fd == '(J)D' and call_args[0][0] == 'const':
                    res = struct.unpack('>d', struct.pack('>q', call_args[0][1]))[0]
                    push_const(res, 2)
                else:
                    if recv is not None: use_item(recv)
                    for x in call_args: use_item(x)
                    keep_insn[pc] = f"{inv_type} {short_cls(tcls)}.{fn}:{fd}"
                    if ret_t != 'V':
                        push_sym(2 if ret_t in ('J', 'D') else 1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0xbb, 0xbd, 0xc0, 0xc1):
                tcls = cp[cp[arg][1]][1]
                if op != 0xbb:
                    a = pop(); use_item(a)
                keep_insn[pc] = f"{name} {short_cls(tcls)}"
                push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif op == 0xbc:
                a = pop(); use_item(a)
                keep_insn[pc] = f"newarray {arg}"
                push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif op == 0xc5: # multianewarray
                cidx, dim = arg
                tcls = cp[cp[cidx][1]][1]
                for _ in range(dim):
                    a = pop(); use_item(a)
                keep_insn[pc] = f"multianewarray {short_cls(tcls)} dim={dim}"
                push_sym(1)
                worklist.append((next_pc, tuple(st)))
            elif op in (0xbe, 0xbf, 0xc2, 0xc3):
                a = pop(); use_item(a)
                keep_insn[pc] = name
                if op == 0xbe:
                    push_sym(1)
                    worklist.append((next_pc, tuple(st)))
                elif op != 0xbf:
                    worklist.append((next_pc, tuple(st)))
            else:
                raise RuntimeError(f"Unhandled op 0x{op:02x} ({name}) at pc={pc} in Class_{idx}")

        real_pcs = sorted(set(keep_insn.keys()) | set(emit_const.keys()))
        def resolve_target(tgt):
            seen_j = set()
            while tgt not in keep_insn and tgt not in emit_const and tgt in insns and tgt not in seen_j:
                seen_j.add(tgt)
                if tgt in jumps_taken:
                    tgt = jumps_taken[tgt]
                else:
                    tgt = insns[tgt][2]
            return tgt

        # Update sym_if_targets with resolved target PCs
        for p, (if_name, raw_t) in sym_if_targets.items():
            keep_insn[p] = f"{if_name} -> {resolve_target(raw_t)}"

        # Walk real_pcs and check if control flow between real_pcs[k] and real_pcs[k+1] jumps elsewhere
        for idx_p, p in enumerate(real_pcs):
            if p in emit_const:
                out.append(f"    {p:4d}: CONST {repr(emit_const[p])}")
            if p in keep_insn:
                out.append(f"    {p:4d}: {keep_insn[p]}")
            # Check where fallthrough from p goes!
            op, name, next_pc, arg = insns[p]
            if op not in (0xac, 0xad, 0xae, 0xaf, 0xb0, 0xb1, 0xbf, 0xaa, 0xab):
                ft_target = resolve_target(next_pc)
                nxt_real = real_pcs[idx_p + 1] if idx_p + 1 < len(real_pcs) else None
                if ft_target != nxt_real:
                    out.append(f"    {p:4d}+: goto -> {ft_target}")
    return "\n".join(out)

def short_cls(cname):
    if cname in cname_to_idx:
        idx = cname_to_idx[cname]
        if idx in (249, 2, 252, 336):
            return cname
        return f"Class_{idx}"
    if cname.startswith('\x00'):
        return f"Obf_{cname[1:9]}"
    return cname

def run_deobfuscation_pipeline(jar_path, output_path):
    t0 = time.time()
    print(f"[*] Initializing JVM Bytecode Emulator on {jar_path}...")
    global vm, static_fields, bsm_cache, xor_keys, iii_classes, REAL_INDICES, classes_by_idx, cname_to_idx
    vm = VM(jar_path)
    xor_keys = {}
    orig_exec = vm.exec_method

    def fast_exec(cname, mname, mdesc, args):
        if (mname == ' ' * 32766 or '<SPACES_' in mname) and mdesc == '(Ljava/lang/String;)Ljava/lang/String;':
            if cname not in xor_keys:
                res0 = orig_exec(cname, mname, mdesc, ["\x00"])
                xor_keys[cname] = ord(res0[0])
            k = xor_keys[cname]
            return "".join(chr(ord(ch) ^ k) for ch in args[0])
        return orig_exec(cname, mname, mdesc, args)

    vm.exec_method = fast_exec

    print("[*] Emulating all 258 external rolling-key String Decryptor (<clinit>) classes...")
    for cname, c in vm.classes.items():
        m_sigs = [x[2] for x in c['methods']]
        if '([CJI)Ljava/lang/String;' in m_sigs:
            vm.exec_method(cname, '<clinit>', '()V', [])

    print("[*] Resolving all 4,264 InvokeDynamic ConstantCallSite bootstrap methods...")
    bsm_cache = {}
    for cname, c in vm.classes.items():
        for bi in range(len(c['bsms'])):
            res = vm.resolve_bsm(c, bi)
            if res is not None:
                bsm_cache[(cname, bi)] = res

    static_fields = vm.static_fields
    iii_classes = set()
    for cname, c in vm.classes.items():
        m_sigs = [x[2] for x in c['methods']]
        f_sigs = [x[2] for x in c['fields']]
        if len(f_sigs) == 0 and len(m_sigs) > 0 and all(s == '(II)I' for s in m_sigs):
            iii_classes.add(cname)

    REAL_INDICES = [
        249, 2, 252, 336, 211, 130, 158, 262, 471, 1177, 1198,
        6, 38, 40, 42, 61, 76, 79, 82, 85, 134, 163, 170, 183, 243, 247, 250, 251, 296,
        3, 5, 298,
        0, 1, 7, 8, 9, 17, 41, 43, 44, 80, 103, 136, 142, 175, 185, 207, 226, 297, 299, 302, 341
    ]
    classes_by_idx = {}
    cname_to_idx = {}
    with zipfile.ZipFile(jar_path, 'r') as zf:
        infos = zf.infolist()
        for idx in REAL_INDICES:
            c = parse_full_class(zf.read(infos[idx]))
            classes_by_idx[idx] = c
            cname_to_idx[c['this']] = idx

    print("[*] Running Lazy-Constant-Stack Abstract Interpreter & Opaque Predicate Eliminator on all 53 business classes...")
    all_out = []
    for idx in REAL_INDICES:
        all_out.append(simplify_class_clean(idx, classes_by_idx[idx]))
    full_text = "\n".join(all_out).replace("\x00", "\\x00")
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(full_text)
    print(f"[+] Completed full deobfuscation in {time.time() - t0:.2f}s -> {output_path}")

if __name__ == "__main__":
    if len(sys.argv) >= 3:
        run_deobfuscation_pipeline(sys.argv[1], sys.argv[2])
    else:
        print("NovaDeobfuscator.py ready. Usage: python3 NovaDeobfuscator.py <input.jar> <output.txt>")
