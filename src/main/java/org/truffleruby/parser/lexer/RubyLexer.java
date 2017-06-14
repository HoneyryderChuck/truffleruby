/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2004-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2004-2005 David Corbin <dcorbin@users.sourceforge.net>
 * Copyright (C) 2005 Zach Dennis <zdennis@mktec.com>
 * Copyright (C) 2006 Thomas Corbat <tcorbat@hsr.ch>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 *
 * Copyright (c) 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 ***** END LICENSE BLOCK *****/
package org.truffleruby.parser.lexer;

import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.collections.ByteArrayBuilder;
import org.truffleruby.core.regexp.ClassicRegexp;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.StringSupport;
import org.truffleruby.language.SourceIndexLength;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.parser.RubyWarnings;
import org.truffleruby.parser.SafeDoubleParser;
import org.truffleruby.parser.ast.BackRefParseNode;
import org.truffleruby.parser.ast.BigRationalParseNode;
import org.truffleruby.parser.ast.BignumParseNode;
import org.truffleruby.parser.ast.ComplexParseNode;
import org.truffleruby.parser.ast.FixnumParseNode;
import org.truffleruby.parser.ast.FloatParseNode;
import org.truffleruby.parser.ast.ListParseNode;
import org.truffleruby.parser.ast.NthRefParseNode;
import org.truffleruby.parser.ast.NumericParseNode;
import org.truffleruby.parser.ast.ParseNode;
import org.truffleruby.parser.ast.RationalParseNode;
import org.truffleruby.parser.ast.StrParseNode;
import org.truffleruby.parser.parser.ParserRopeOperations;
import org.truffleruby.parser.parser.ParserSupport;
import org.truffleruby.parser.parser.RubyParser;
import org.truffleruby.parser.parser.Tokens;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;

import static org.truffleruby.core.rope.CodeRange.CR_7BIT;

/*
 * This is a port of the MRI lexer to Java.
 */
public class RubyLexer {

    private final ParserRopeOperations parserRopeOperations = new ParserRopeOperations();

    private static final HashMap<String, Keyword> map;

    static {
        map = new HashMap<>();

        map.put("end", Keyword.END);
        map.put("else", Keyword.ELSE);
        map.put("case", Keyword.CASE);
        map.put("ensure", Keyword.ENSURE);
        map.put("module", Keyword.MODULE);
        map.put("elsif", Keyword.ELSIF);
        map.put("def", Keyword.DEF);
        map.put("rescue", Keyword.RESCUE);
        map.put("not", Keyword.NOT);
        map.put("then", Keyword.THEN);
        map.put("yield", Keyword.YIELD);
        map.put("for", Keyword.FOR);
        map.put("self", Keyword.SELF);
        map.put("false", Keyword.FALSE);
        map.put("retry", Keyword.RETRY);
        map.put("return", Keyword.RETURN);
        map.put("true", Keyword.TRUE);
        map.put("if", Keyword.IF);
        map.put("defined?", Keyword.DEFINED_P);
        map.put("super", Keyword.SUPER);
        map.put("undef", Keyword.UNDEF);
        map.put("break", Keyword.BREAK);
        map.put("in", Keyword.IN);
        map.put("do", Keyword.DO);
        map.put("nil", Keyword.NIL);
        map.put("until", Keyword.UNTIL);
        map.put("unless", Keyword.UNLESS);
        map.put("or", Keyword.OR);
        map.put("next", Keyword.NEXT);
        map.put("when", Keyword.WHEN);
        map.put("redo", Keyword.REDO);
        map.put("and", Keyword.AND);
        map.put("begin", Keyword.BEGIN);
        map.put("__LINE__", Keyword.__LINE__);
        map.put("class", Keyword.CLASS);
        map.put("__FILE__", Keyword.__FILE__);
        map.put("END", Keyword.LEND);
        map.put("BEGIN", Keyword.LBEGIN);
        map.put("while", Keyword.WHILE);
        map.put("alias", Keyword.ALIAS);
        map.put("__ENCODING__", Keyword.__ENCODING__);
    }

    private BignumParseNode newBignumNode(String value, int radix) {
        return new BignumParseNode(getPosition(), new BigInteger(value, radix));
    }

    private FixnumParseNode newFixnumNode(String value, int radix) throws NumberFormatException {
        return new FixnumParseNode(getPosition(), Long.parseLong(value, radix));
    }
    
    private ParseNode newRationalNode(String value, int radix) throws NumberFormatException {
        try {
            return new RationalParseNode(getPosition(), Long.parseLong(value, radix), 1);
        } catch (NumberFormatException e) {
            return new BigRationalParseNode(getPosition(), new BigInteger(value, radix), BigInteger.ONE);
        }
    }
    
    private ComplexParseNode newComplexNode(NumericParseNode number) {
        return new ComplexParseNode(getPosition(), number);
    }

    protected void ambiguousOperator(String op, String syn) {
        warnings.warn(RubyWarnings.ID.AMBIGUOUS_ARGUMENT, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "`" + op + "' after local variable or literal is interpreted as binary operator");
        warnings.warn(RubyWarnings.ID.AMBIGUOUS_ARGUMENT, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "even though it seems like " + syn);
    }

    public Source getSource() {
        return src.getSource();
    }

    public enum Keyword {
        END ("end", Tokens.kEND, Tokens.kEND, EXPR_END),
        ELSE ("else", Tokens.kELSE, Tokens.kELSE, EXPR_BEG),
        CASE ("case", Tokens.kCASE, Tokens.kCASE, EXPR_BEG),
        ENSURE ("ensure", Tokens.kENSURE, Tokens.kENSURE, EXPR_BEG),
        MODULE ("module", Tokens.kMODULE, Tokens.kMODULE, EXPR_BEG),
        ELSIF ("elsif", Tokens.kELSIF, Tokens.kELSIF, EXPR_BEG),
        DEF ("def", Tokens.kDEF, Tokens.kDEF, EXPR_FNAME),
        RESCUE ("rescue", Tokens.kRESCUE, Tokens.kRESCUE_MOD, EXPR_MID),
        NOT ("not", Tokens.kNOT, Tokens.kNOT, EXPR_ARG),
        THEN ("then", Tokens.kTHEN, Tokens.kTHEN, EXPR_BEG),
        YIELD ("yield", Tokens.kYIELD, Tokens.kYIELD, EXPR_ARG),
        FOR ("for", Tokens.kFOR, Tokens.kFOR, EXPR_BEG),
        SELF ("self", Tokens.kSELF, Tokens.kSELF, EXPR_END),
        FALSE ("false", Tokens.kFALSE, Tokens.kFALSE, EXPR_END),
        RETRY ("retry", Tokens.kRETRY, Tokens.kRETRY, EXPR_END),
        RETURN ("return", Tokens.kRETURN, Tokens.kRETURN, EXPR_MID),
        TRUE ("true", Tokens.kTRUE, Tokens.kTRUE, EXPR_END),
        IF ("if", Tokens.kIF, Tokens.kIF_MOD, EXPR_BEG),
        DEFINED_P ("defined?", Tokens.kDEFINED, Tokens.kDEFINED, EXPR_ARG),
        SUPER ("super", Tokens.kSUPER, Tokens.kSUPER, EXPR_ARG),
        UNDEF ("undef", Tokens.kUNDEF, Tokens.kUNDEF, EXPR_FNAME),
        BREAK ("break", Tokens.kBREAK, Tokens.kBREAK, EXPR_MID),
        IN ("in", Tokens.kIN, Tokens.kIN, EXPR_BEG),
        DO ("do", Tokens.kDO, Tokens.kDO, EXPR_BEG),
        NIL ("nil", Tokens.kNIL, Tokens.kNIL, EXPR_END),
        UNTIL ("until", Tokens.kUNTIL, Tokens.kUNTIL_MOD, EXPR_BEG),
        UNLESS ("unless", Tokens.kUNLESS, Tokens.kUNLESS_MOD, EXPR_BEG),
        OR ("or", Tokens.kOR, Tokens.kOR, EXPR_BEG),
        NEXT ("next", Tokens.kNEXT, Tokens.kNEXT, EXPR_MID),
        WHEN ("when", Tokens.kWHEN, Tokens.kWHEN, EXPR_BEG),
        REDO ("redo", Tokens.kREDO, Tokens.kREDO, EXPR_END),
        AND ("and", Tokens.kAND, Tokens.kAND, EXPR_BEG),
        BEGIN ("begin", Tokens.kBEGIN, Tokens.kBEGIN, EXPR_BEG),
        __LINE__ ("__LINE__", Tokens.k__LINE__, Tokens.k__LINE__, EXPR_END),
        CLASS ("class", Tokens.kCLASS, Tokens.kCLASS, EXPR_CLASS),
        __FILE__("__FILE__", Tokens.k__FILE__, Tokens.k__FILE__, EXPR_END),
        LEND ("END", Tokens.klEND, Tokens.klEND, EXPR_END),
        LBEGIN ("BEGIN", Tokens.klBEGIN, Tokens.klBEGIN, EXPR_END),
        WHILE ("while", Tokens.kWHILE, Tokens.kWHILE_MOD, EXPR_BEG),
        ALIAS ("alias", Tokens.kALIAS, Tokens.kALIAS, EXPR_FNAME),
        __ENCODING__("__ENCODING__", Tokens.k__ENCODING__, Tokens.k__ENCODING__, EXPR_END);
        
        public final String name;
        public final int id0;
        public final int id1;
        public final int state;
        
        Keyword(String name, int id0, int id1, int state) {
            this.name = name;
            this.id0 = id0;
            this.id1 = id1;
            this.state = state;
        }
    }
    
    public static Keyword getKeyword(String str) {
        return map.get(str);
    }
    
    // Used for tiny smidgen of grammar in lexer (see setParserSupport())
    private ParserSupport parserSupport = null;

    // What handles warnings
    private RubyWarnings warnings;

    public int tokenize_ident(int result) {
        // FIXME: Get token from newtok index to lex_p?
        String value = createTokenString();

        if (isLexState(last_state, EXPR_DOT|EXPR_FNAME) && parserSupport.getCurrentScope().isDefined(value) >= 0) {
            setState(EXPR_END);
        }

        yaccValue = value.intern();
        return result;
    }

    private StrTerm lex_strterm;

    public RubyLexer(ParserSupport support, LexerSource source, RubyWarnings warnings) {
        this.src = source;
        this.parserSupport = support;
        this.warnings = warnings;
        reset();
    }

    @Deprecated
    public RubyLexer(ParserSupport support, LexerSource source) {
        this.src = source;
        this.parserSupport = support;
        reset();
    }

    public void reset() {
        superReset();
        lex_strterm = null;
        ruby_sourceline = 1 + src.getLineStartOffset();
        updateLineOffset();

        // nextc will increment for the first character on the first line
        ruby_sourceline--;

        parser_prepare();
    }

    public int nextc() {
        if (lex_p == lex_pend) {
            line_offset += lex_pend;

            Rope v = lex_nextline;
            lex_nextline = null;

            if (v == null) {
                if (eofp) return EOF;

                if (src == null || (v = src.gets()) == null) {
                    eofp = true;
                    lex_goto_eol();
                    return EOF;
                }
            }

            if (heredoc_end > 0) {
                ruby_sourceline = heredoc_end;
                updateLineOffset();
                heredoc_end = 0;
            }
            ruby_sourceline++;
            updateLineOffset();
            line_count++;
            lex_pbeg = lex_p = 0;
            lex_pend = lex_p + v.byteLength();
            lexb = v;
            flush();
            lex_lastline = v;
        }

        int c = p(lex_p);
        lex_p++;
        if (c == '\r') {
            if (peek('\n')) {
                lex_p++;
                c = '\n';
            } else if (ruby_sourceline > last_cr_line) {
                last_cr_line = ruby_sourceline;
                warnings.warn(RubyWarnings.ID.VOID_VALUE_EXPRESSION, getFile(), ruby_sourceline, "encountered \\r in middle of line, treated as a mere space");
                c = ' ';
            }
        }

        return c;
    }

    public void heredoc_dedent(ParseNode root) {
        int indent = heredoc_indent;

        if (indent <= 0 || root == null) return;

        if (root instanceof StrParseNode) {
            StrParseNode str = (StrParseNode) root;
            str.setValue(dedent_string(str.getValue(), indent));
        } else if (root instanceof ListParseNode) {
            ListParseNode list = (ListParseNode) root;
            int length = list.size();
            int currentLine = -1;
            for (int i = 0; i < length; i++) {
                ParseNode child = list.get(i);
                if (currentLine == child.getPosition().toSourceSection(src.getSource()).getStartLine() - 1) continue;  // Only process first element on a line?

                currentLine = child.getPosition().toSourceSection(src.getSource()).getStartLine() - 1;                 // New line

                if (child instanceof StrParseNode) {
                    final StrParseNode childStrNode = (StrParseNode) child;
                    childStrNode.setValue(dedent_string(childStrNode.getValue(), indent));
                }
            }
        }
    }

    public void compile_error(String message) {
        throw new SyntaxException(SyntaxException.PID.BAD_HEX_NUMBER, getFile(), ruby_sourceline, RopeOperations.decodeRope(StandardCharsets.ISO_8859_1, lexb), message);
    }

    // FIXME: How does lexb.toString() vs getCurrentLine() differ.
    public void compile_error(SyntaxException.PID pid, String message) {
        String src = RopeOperations.decodeRope(lex_lastline);
        throw new SyntaxException(pid, getFile(), ruby_sourceline, src, message);
    }

    public void heredoc_restore(HeredocTerm here) {
        Rope line = here.lastLine;
        lex_lastline = line;
        lex_pbeg = 0;
        lex_pend = lex_pbeg + line.byteLength();
        lex_p = lex_pbeg + here.nth;
        lexb = line;
        heredoc_end = ruby_sourceline;
        ruby_sourceline = here.line;
        updateLineOffset();
        flush();
    }

    public int nextToken() {
        token = yylex();
        return token == EOF ? 0 : token;
    }

    public SourceIndexLength getPosition() {
        if (tokline != null && ruby_sourceline == ruby_sourceline_when_tokline_created) {
            return tokline;
        }
        assert sourceSectionsMatch();
        return new SourceIndexLength(ruby_sourceline_char_offset, ruby_sourceline_char_length);
    }

    private boolean sourceSectionsMatch() {
        int line = ruby_sourceline;

        if (line == 0) {
            // Reading the position before nextc has run for the first time
            line = 1;
        }

        final SourceSection sectionFromOffsets = src.getSource().createSection(ruby_sourceline_char_offset, ruby_sourceline_char_length);

        final SourceSection sectionFromLine = src.getSource().createSection(line);
        assert sectionFromLine.getStartLine() == line;

        assert sectionFromOffsets.getStartLine() == line;
        assert sectionFromLine.getCharIndex() == sectionFromOffsets.getCharIndex();
        assert sectionFromLine.getCharLength() == sectionFromOffsets.getCharLength();

        return true;
    }

    private void updateLineOffset() {
        if (ruby_sourceline != 0) {
            ruby_sourceline_char_offset = src.getSource().getLineStartOffset(ruby_sourceline);
            ruby_sourceline_char_length = src.getSource().getLineLength(ruby_sourceline);
        }
    }

    /**
     * Parse must pass its support object for some check at bottom of
     * yylex().  Ruby does it this way as well (i.e. a little parsing
     * logic in the lexer).
     *
     * @param parserSupport
     */
    public void setParserSupport(ParserSupport parserSupport) {
        this.parserSupport = parserSupport;
    }

    protected void setCompileOptionFlag(String name, Rope value) {
        if (tokenSeen) {
            warnings.warn(RubyWarnings.ID.ACCESSOR_MODULE_FUNCTION, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "`" + name + "' is ignored after any tokens");
            return;
        }

        int b = asTruth(name, value);
        if (b < 0) return;

        // Enebo: This is a hash in MRI for multiple potential compile options but we currently only support one.
        // I am just going to set it and when a second is done we will reevaluate how they are populated.
        parserSupport.getConfiguration().setFrozenStringLiteral(b == 1);
    }

    private final Rope TRUE = RopeOperations.create(new byte[]{'t', 'r', 'u', 'e'}, ASCIIEncoding.INSTANCE, CR_7BIT);
    private final Rope FALSE = RopeOperations.create(new byte[]{'f', 'a', 'l', 's', 'e'}, ASCIIEncoding.INSTANCE, CR_7BIT);

    protected int asTruth(String name, Rope value) {
        int result = RopeOperations.caseInsensitiveCmp(value, TRUE);
        if (result == 0) return 1;

        result = RopeOperations.caseInsensitiveCmp(value, FALSE);
        if (result == 0) return 0;

        warnings.warn(RubyWarnings.ID.ACCESSOR_MODULE_FUNCTION, "invalid value for " + name + ": " + value);
        return -1;
    }

    protected void setTokenInfo(String name, Rope value) {

    }

    protected void setEncoding(Rope name) {
        final RubyContext context = parserSupport.getConfiguration().getContext();
        Encoding newEncoding = Layouts.ENCODING.getEncoding(context.getEncodingManager().getRubyEncoding(RopeOperations.decodeRope(StandardCharsets.ISO_8859_1, name)));

        if (newEncoding == null) throw new RaiseException(context.getCoreExceptions().argumentError("unknown encoding name: " + RopeOperations.decodeRope(StandardCharsets.ISO_8859_1, name), null));
        if (!newEncoding.isAsciiCompatible()) throw new RaiseException(context.getCoreExceptions().argumentError(RopeOperations.decodeRope(StandardCharsets.ISO_8859_1, name) + " is not ASCII compatible", null));

        setEncoding(newEncoding);
    }

    public StrTerm getStrTerm() {
        return lex_strterm;
    }

    public void setStrTerm(StrTerm strterm) {
        this.lex_strterm = strterm;
    }

    public void setWarnings(RubyWarnings warnings) {
        this.warnings = warnings;
    }

    private int considerComplex(int token, int suffix) {
        if ((suffix & SUFFIX_I) == 0) {
            return token;
        } else {
            yaccValue = newComplexNode((NumericParseNode) yaccValue);
            return RubyParser.tIMAGINARY;
        }
    }

    private int getFloatToken(String number, int suffix) {
        if ((suffix & SUFFIX_R) != 0) {
            BigDecimal bd = new BigDecimal(number);
            BigDecimal denominator = BigDecimal.ONE.scaleByPowerOfTen(bd.scale());
            BigDecimal numerator = bd.multiply(denominator);

            try {
                yaccValue = new RationalParseNode(getPosition(), numerator.longValueExact(), denominator.longValueExact());
            } catch (ArithmeticException ae) {
                // FIXME: Rational supports Bignum numerator and denominator
                compile_error(SyntaxException.PID.RATIONAL_OUT_OF_RANGE, "Rational (" + numerator + "/" + denominator + ") out of range.");
            }
            return considerComplex(Tokens.tRATIONAL, suffix);
        }

        double d;
        try {
            d = SafeDoubleParser.parseDouble(number);
        } catch (NumberFormatException e) {
            warnings.warn(RubyWarnings.ID.FLOAT_OUT_OF_RANGE, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "Float " + number + " out of range.");

            d = number.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        yaccValue = new FloatParseNode(getPosition(), d);
        return considerComplex(Tokens.tFLOAT, suffix);
    }

    private int getIntegerToken(String value, int radix, int suffix) {
        ParseNode literalValue;

        if ((suffix & SUFFIX_R) != 0) {
            literalValue = newRationalNode(value, radix);
        } else {
            try {
                literalValue = newFixnumNode(value, radix);
            } catch (NumberFormatException e) {
                literalValue = newBignumNode(value, radix);
            }
        }

        yaccValue = literalValue;
        return considerComplex(Tokens.tINTEGER, suffix);
    }

    public StrParseNode createStr(RopeBuilder buffer, int flags) {
        return createStr(buffer.toRope(), flags);
    }

    // STR_NEW3/parser_str_new
    public StrParseNode createStr(Rope buffer, int flags) {
        Encoding bufferEncoding = buffer.getEncoding();
        CodeRange codeRange = buffer.getCodeRange();

        if ((flags & STR_FUNC_REGEXP) == 0 && bufferEncoding.isAsciiCompatible()) {
            // If we have characters outside 7-bit range and we are still ascii then change to ascii-8bit
            if (codeRange == CodeRange.CR_7BIT) {
                // Do nothing like MRI
            } else if (getEncoding() == USASCII_ENCODING &&
                    bufferEncoding != UTF8_ENCODING) {
                codeRange = associateEncoding(buffer, ASCII8BIT_ENCODING, codeRange);
                buffer = parserRopeOperations.withEncoding(buffer, ASCII8BIT_ENCODING);
            }
        }

        StrParseNode newStr = new StrParseNode(getPosition(), buffer, codeRange);

        if (parserSupport.getConfiguration().isFrozenStringLiteral()) newStr.setFrozen(true);

        return newStr;
    }

    public static CodeRange associateEncoding(Rope buffer, Encoding newEncoding, CodeRange codeRange) {
        Encoding bufferEncoding = buffer.getEncoding();

        if (newEncoding == bufferEncoding) return codeRange;

        // TODO: Special const error

        if (codeRange != CodeRange.CR_7BIT || !newEncoding.isAsciiCompatible()) {
            return CodeRange.CR_UNKNOWN;
        }

        return codeRange;
    }

    /**
     * What type/kind of quote are we dealing with?
     *
     * @param c first character the the quote construct
     * @return a token that specifies the quote type
     */
    private int parseQuote(int c) {
        int begin, end;
        boolean shortHand;

        // Short-hand (e.g. %{,%.,%!,... versus %Q{).
        if (!Character.isLetterOrDigit(c)) {
            begin = c;
            c = 'Q';
            shortHand = true;
        // Long-hand (e.g. %Q{}).
        } else {
            shortHand = false;
            begin = nextc();
            if (Character.isLetterOrDigit(begin) /* no mb || ismbchar(term)*/) compile_error(SyntaxException.PID.STRING_UNKNOWN_TYPE, "unknown type of %string");
        }
        if (c == EOF || begin == EOF) compile_error(SyntaxException.PID.STRING_HITS_EOF, "unterminated quoted string meets end of file");

        // Figure end-char.  '\0' is special to indicate begin=end and that no nesting?
        switch(begin) {
        case '(': end = ')'; break;
        case '[': end = ']'; break;
        case '{': end = '}'; break;
        case '<': end = '>'; break;
        default:
            end = begin;
            begin = '\0';
        }

        switch (c) {
        case 'Q':
            lex_strterm = new StringTerm(str_dquote, begin ,end);
            yaccValue = "%"+ (shortHand ? (""+end) : ("" + c + begin));
            return Tokens.tSTRING_BEG;

        case 'q':
            lex_strterm = new StringTerm(str_squote, begin, end);
            yaccValue = "%"+c+begin;
            return Tokens.tSTRING_BEG;

        case 'W':
            lex_strterm = new StringTerm(str_dquote | STR_FUNC_QWORDS, begin, end);
            do {c = nextc();} while (Character.isWhitespace(c));
            pushback(c);
            yaccValue = "%"+c+begin;
            return Tokens.tWORDS_BEG;

        case 'w':
            lex_strterm = new StringTerm(/* str_squote | */ STR_FUNC_QWORDS, begin, end);
            do {c = nextc();} while (Character.isWhitespace(c));
            pushback(c);
            yaccValue = "%"+c+begin;
            return Tokens.tQWORDS_BEG;

        case 'x':
            lex_strterm = new StringTerm(str_xquote, begin, end);
            yaccValue = "%"+c+begin;
            return Tokens.tXSTRING_BEG;

        case 'r':
            lex_strterm = new StringTerm(str_regexp, begin, end);
            yaccValue = "%"+c+begin;
            return Tokens.tREGEXP_BEG;

        case 's':
            lex_strterm = new StringTerm(str_ssym, begin, end);
            setState(EXPR_FNAME);
            yaccValue = "%"+c+begin;
            return Tokens.tSYMBEG;

        case 'I':
            lex_strterm = new StringTerm(str_dquote | STR_FUNC_QWORDS, begin, end);
            do {c = nextc();} while (Character.isWhitespace(c));
            pushback(c);
            yaccValue = "%" + c + begin;
            return Tokens.tSYMBOLS_BEG;
        case 'i':
            lex_strterm = new StringTerm(/* str_squote | */STR_FUNC_QWORDS, begin, end);
            do {c = nextc();} while (Character.isWhitespace(c));
            pushback(c);
            yaccValue = "%" + c + begin;
            return Tokens.tQSYMBOLS_BEG;
        default:
            compile_error(SyntaxException.PID.STRING_UNKNOWN_TYPE, "unknown type of %string");
        }
        return -1; // not-reached
    }

    private int hereDocumentIdentifier() {
        int c = nextc();
        int term;

        int func = 0;
        if (c == '-') {
            c = nextc();
            func = STR_FUNC_INDENT;
        } else if (c == '~') {
            c = nextc();
            func = STR_FUNC_INDENT;
            heredoc_indent = Integer.MAX_VALUE;
            heredoc_line_indent = 0;
        }

        Rope markerValue;
        if (c == '\'' || c == '"' || c == '`') {
            if (c == '\'') {
                func |= str_squote;
            } else if (c == '"') {
                func |= str_dquote;
            } else {
                func |= str_xquote;
            }

            newtok(false); // skip past quote type

            term = c;
            while ((c = nextc()) != EOF && c != term) {
                if (!tokadd_mbchar(c)) return EOF;
            }

            if (c == EOF) compile_error("unterminated here document identifier");

            // c == term.  This differs from MRI in that we unwind term symbol so we can make
            // our marker with just tokp and lex_p info (e.g. we don't make second numberBuffer).
            pushback(term);
            markerValue = createTokenByteArrayView();
            nextc();
        } else {
            if (!isIdentifierChar(c)) {
                pushback(c);
                if ((func & STR_FUNC_INDENT) != 0) {
                    pushback(heredoc_indent > 0 ? '~' : '-');
                }
                return 0;
            }
            newtok(true);
            term = '"';
            func |= str_dquote;
            do {
                if (!tokadd_mbchar(c)) return EOF;
            } while ((c = nextc()) != EOF && isIdentifierChar(c));
            pushback(c);
            markerValue = createTokenByteArrayView();
        }

        int len = lex_p - lex_pbeg;
        lex_goto_eol();
        lex_strterm = new HeredocTerm(markerValue, func, len, ruby_sourceline, lex_lastline);

        if (term == '`') {
            yaccValue = "`";
            flush();
            return Tokens.tXSTRING_BEG;
        }

        yaccValue = "\"";
        flush();
        return Tokens.tSTRING_BEG;
    }

    private boolean arg_ambiguous() {
        if (warnings.isVerbose() && !parserSupport.skipTruffleRubiniusWarnings(this)) {
            warnings.warning(RubyWarnings.ID.AMBIGUOUS_ARGUMENT, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "Ambiguous first argument; make sure.");
        }
        return true;
    }

    /**
     *  Returns the next token. Also sets yyVal is needed.
     *
     *@return    Description of the Returned Value
     */
    @SuppressWarnings("fallthrough")
    private int yylex() {
        int c;
        boolean spaceSeen = false;
        boolean commandState;
        boolean tokenSeen = this.tokenSeen;

        if (lex_strterm != null) {
            int tok = lex_strterm.parseString(this);

            if (tok == Tokens.tSTRING_END && (lex_strterm.getFlags() & STR_FUNC_LABEL) != 0) {
                if ((isLexState(lex_state, EXPR_BEG|EXPR_ENDFN) && !conditionState.isInState() ||
                        isARG()) && isLabelSuffix()) {
                    nextc();
                    tok = Tokens.tLABEL_END;
                    setState(EXPR_BEG|EXPR_LABEL);
                    lex_strterm = null;
                }
            }

            if (tok == Tokens.tSTRING_END || tok == Tokens.tREGEXP_END) {
                lex_strterm = null;
                setState(EXPR_END);
            }

            return tok;
        }

        commandState = commandStart;
        commandStart = false;
        this.tokenSeen = true;

        loop: for(;;) {
            last_state = lex_state;
            c = nextc();
            switch(c) {
            case '\000': /* NUL */
            case '\004': /* ^D */
            case '\032': /* ^Z */
            case EOF:	 /* end of script. */
                return EOF;

                /* white spaces */
            case ' ': case '\t': case '\f': case '\r':
            case '\13': /* '\v' */
                getPosition();
                spaceSeen = true;
                continue;
            case '#': {	/* it's a comment */
                this.tokenSeen = tokenSeen;

                // There are no magic comments that can affect any runtime options after a token has been seen, so there's
                // no point in looking for them. However, if warnings are enabled, we do need to scan for the magic comment
                // so we can report that it will be ignored.
                if (!tokenSeen || (!TruffleOptions.AOT && parserSupport.getContext().getCoreLibrary().warningsEnabled())) {
                    if (!parseMagicComment(lexb, lex_p, lex_pend - lex_p)) {
                        if (comment_at_top()) set_file_encoding(lex_p, lex_pend);
                    }
                }

                lex_p = lex_pend;
            }
            /* fall through */
            case '\n': {
                this.tokenSeen = tokenSeen;
                boolean normalArg = isLexState(lex_state, EXPR_BEG | EXPR_CLASS | EXPR_FNAME | EXPR_DOT) &&
                        !isLexState(lex_state, EXPR_LABELED);
                if (normalArg || isLexStateAll(lex_state, EXPR_ARG | EXPR_LABELED)) {
                    if (!normalArg && inKwarg) {
                        commandStart = true;
                        setState(EXPR_BEG);
                        return '\n';
                    }
                    continue loop;
                }

                boolean done = false;
                while (!done) {
                    c = nextc();

                    switch (c) {
                    case ' ': case '\t': case '\f': case '\r': case '\13': /* '\v' */
                        spaceSeen = true;
                        continue;
                    case '&':
                    case '.': {
                        if (peek('.') == (c == '&')) {
                            pushback(c);

                            continue loop;
                        }
                    }
                    default:
                    case -1:		// EOF (ENEBO: After default?
                        done = true;
                    }
                }

                if (c == -1) return EOF;

                pushback(c);
                getPosition();

                commandStart = true;
                setState(EXPR_BEG);
                return '\n';
            }
            case '*':
                return star(spaceSeen);
            case '!':
                return bang();
            case '=':
                // documentation nodes
                if (was_bol()) {
                    if (strncmp(parserRopeOperations.makeShared(lexb, lex_p, lex_pend - lex_p), BEGIN_DOC_MARKER, BEGIN_DOC_MARKER.byteLength()) &&
                            Character.isWhitespace(p(lex_p + 5))) {
                        for (;;) {
                            lex_goto_eol();

                            c = nextc();

                            if (c == EOF) {
                                compile_error("embedded document meets end of file");
                                return EOF;
                            }

                            if (c != '=') continue;

                            if (strncmp(parserRopeOperations.makeShared(lexb, lex_p, lex_pend - lex_p), END_DOC_MARKER, END_DOC_MARKER.byteLength()) &&
                                    (lex_p + 3 == lex_pend || Character.isWhitespace(p(lex_p + 3)))) {
                                break;
                            }
                        }
                        lex_goto_eol();

                        continue loop;
                    }
                }

                setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

                c = nextc();
                if (c == '=') {
                    c = nextc();
                    if (c == '=') {
                        yaccValue = "===";
                        return Tokens.tEQQ;
                    }
                    pushback(c);
                    yaccValue = "==";
                    return Tokens.tEQ;
                }
                if (c == '~') {
                    yaccValue = "=~";
                    return Tokens.tMATCH;
                } else if (c == '>') {
                    yaccValue = "=>";
                    return Tokens.tASSOC;
                }
                pushback(c);
                yaccValue = "=";
                return '=';

            case '<':
                return lessThan(spaceSeen);
            case '>':
                return greaterThan();
            case '"':
                return doubleQuote(commandState);
            case '`':
                return backtick(commandState);
            case '\'':
                return singleQuote(commandState);
            case '?':
                return questionMark();
            case '&':
                return ampersand(spaceSeen);
            case '|':
                return pipe();
            case '+':
                return plus(spaceSeen);
            case '-':
                return minus(spaceSeen);
            case '.':
                return dot();
            case '0' : case '1' : case '2' : case '3' : case '4' :
            case '5' : case '6' : case '7' : case '8' : case '9' :
                return parseNumber(c);
            case ')':
                return rightParen();
            case ']':
                return rightBracket();
            case '}':
                return rightCurly();
            case ':':
                return colon(spaceSeen);
            case '/':
                return slash(spaceSeen);
            case '^':
                return caret();
            case ';':
                commandStart = true;
                setState(EXPR_BEG);
                yaccValue = ";";
                return ';';
            case ',':
                return comma(c);
            case '~':
                return tilde();
            case '(':
                return leftParen(spaceSeen);
            case '[':
                return leftBracket(spaceSeen);
            case '{':
            	return leftCurly();
            case '\\':
                c = nextc();
                if (c == '\n') {
                    spaceSeen = true;
                    continue;
                }
                pushback(c);
                yaccValue = "\\";
                return '\\';
            case '%':
                return percent(spaceSeen);
            case '$':
                return dollar();
            case '@':
                return at();
            case '_':
                if (was_bol() && whole_match_p(END_MARKER, false)) {
                    line_offset += lex_pend;
                    __end__seen = true;
                    eofp = true;

                    lex_goto_eol();
                    return EOF;
                }
                return identifier(c, commandState);
            default:
                return identifier(c, commandState);
            }
        }
    }

    private int identifierToken(int result, String value) {
        if (result == Tokens.tIDENTIFIER && !isLexState(last_state, EXPR_DOT|EXPR_FNAME) &&
                parserSupport.getCurrentScope().isDefined(value) >= 0) {
            setState(EXPR_END);
        }

        yaccValue = value;
        return result;
    }

    private int ampersand(boolean spaceSeen) {
        int c = nextc();

        switch (c) {
        case '&':
            setState(EXPR_BEG);
            if ((c = nextc()) == '=') {
                yaccValue = "&&";
                setState(EXPR_BEG);
                return Tokens.tOP_ASGN;
            }
            pushback(c);
            yaccValue = "&&";
            return Tokens.tANDOP;
        case '=':
            yaccValue = "&";
            setState(EXPR_BEG);
            return Tokens.tOP_ASGN;
        case '.':
            setState(EXPR_DOT);
            yaccValue = "&.";
            return Tokens.tANDDOT;
        }
        pushback(c);

        //tmpPosition is required because getPosition()'s side effects.
        //if the warning is generated, the getPosition() on line 954 (this line + 18) will create
        //a wrong position if the "inclusive" flag is not set.
        SourceIndexLength tmpPosition = getPosition();
        if (isSpaceArg(c, spaceSeen)) {
            if (warnings.isVerbose())
                warnings.warning(RubyWarnings.ID.ARGUMENT_AS_PREFIX, getFile(), tmpPosition.toSourceSection(src.getSource()).getStartLine(), "`&' interpreted as argument prefix");
            c = Tokens.tAMPER;
        } else if (isBEG()) {
            c = Tokens.tAMPER;
        } else {
            warn_balanced(c, spaceSeen, "&", "argument prefix");
            c = Tokens.tAMPER2;
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        yaccValue = "&";
        return c;
    }

    // MRI: parser_magic_comment
    public boolean parseMagicComment(Rope magicLine, int magicLineOffset, int magicLineLength) {
        int length = magicLineLength;

        if (length <= 7) return false;
        int beg = magicCommentMarker(magicLine, magicLineOffset);
        if (beg >= 0) {
            int end = magicCommentMarker(magicLine, beg);
            if (end < 0) return false;
            length = end - beg - 3; // -3 is to backup over end just found
        } else {
            beg = magicLineOffset;
        }

        int begin = beg;
        Matcher matcher = magicRegexp.matcher(magicLine.getBytes(), begin, begin + length);
        int result = ClassicRegexp.matcherSearch(matcher, begin, begin + length, Option.NONE);

        if (result < 0) return false;

        // Regexp is guaranteed to have three matches
        int begs[] = matcher.getRegion().beg;
        int ends[] = matcher.getRegion().end;
        String name = RopeOperations.decodeRope(StandardCharsets.ISO_8859_1, magicLine).subSequence(beg + begs[1], beg + ends[1]).toString().replace('-', '_');
        Rope value = parserRopeOperations.makeShared(magicLine, beg + begs[2], ends[2] - begs[2]);

        if ("coding".equalsIgnoreCase(name) || "encoding".equalsIgnoreCase(name)) {
            magicCommentEncoding(value);
        } else if ("frozen_string_literal".equalsIgnoreCase(name)) {
            setCompileOptionFlag(name, value);
        } else if ("warn_indent".equalsIgnoreCase(name)) {
            setTokenInfo(name, value);
        } else {
            return false;
        }

        return true;
    }

    private int at() {
        newtok(true);
        int c = nextc();
        int result;
        if (c == '@') {
            c = nextc();
            result = Tokens.tCVAR;
        } else {
            result = Tokens.tIVAR;
        }

        if (c == EOF || Character.isSpaceChar(c)) {
            if (result == Tokens.tIVAR) {
                compile_error("`@' without identifiers is not allowed as an instance variable name");
            }

            compile_error("`@@' without identifiers is not allowed as a class variable name");
        } else if (Character.isDigit(c) || !isIdentifierChar(c)) {
            pushback(c);
            if (result == Tokens.tIVAR) {
                compile_error(SyntaxException.PID.IVAR_BAD_NAME, "`@" + ((char) c) + "' is not allowed as an instance variable name");
            }
            compile_error(SyntaxException.PID.CVAR_BAD_NAME, "`@@" + ((char) c) + "' is not allowed as a class variable name");
        }

        if (!tokadd_ident(c)) return EOF;

        last_state = lex_state;
        setState(EXPR_END);

        return tokenize_ident(result);
    }

    private int backtick(boolean commandState) {
        yaccValue = "`";

        if (isLexState(lex_state, EXPR_FNAME)) {
            setState(EXPR_ENDFN);
            return Tokens.tBACK_REF2;
        }
        if (isLexState(lex_state, EXPR_DOT)) {
            setState(commandState ? EXPR_CMDARG : EXPR_ARG);

            return Tokens.tBACK_REF2;
        }

        lex_strterm = new StringTerm(str_xquote, '\0', '`');
        return Tokens.tXSTRING_BEG;
    }

    private int bang() {
        int c = nextc();

        if (isAfterOperator()) {
            setState(EXPR_ARG);
            if (c == '@') {
                yaccValue = "!";
                return Tokens.tBANG;
            }
        } else {
            setState(EXPR_BEG);
        }

        switch (c) {
        case '=':
            yaccValue = "!=";

            return Tokens.tNEQ;
        case '~':
            yaccValue = "!~";

            return Tokens.tNMATCH;
        default: // Just a plain bang
            pushback(c);
            yaccValue = "!";

            return Tokens.tBANG;
        }
    }

    private int caret() {
        int c = nextc();
        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = "^";
            return Tokens.tOP_ASGN;
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        pushback(c);
        yaccValue = "^";
        return Tokens.tCARET;
    }

    private int colon(boolean spaceSeen) {
        int c = nextc();

        if (c == ':') {
            if (isBEG() || isLexState(lex_state, EXPR_CLASS) || (isARG() && spaceSeen)) {
                setState(EXPR_BEG);
                yaccValue = "::";
                return Tokens.tCOLON3;
            }
            setState(EXPR_DOT);
            yaccValue = ":";
            return Tokens.tCOLON2;
        }

        if (isEND() || Character.isWhitespace(c) || c == '#') {
            pushback(c);
            setState(EXPR_BEG);
            yaccValue = ":";
            warn_balanced(c, spaceSeen, ":", "symbol literal");
            return ':';
        }

        switch (c) {
        case '\'':
            lex_strterm = new StringTerm(str_ssym, '\0', c);
            break;
        case '"':
            lex_strterm = new StringTerm(str_dsym, '\0', c);
            break;
        default:
            pushback(c);
            break;
        }

        setState(EXPR_FNAME);
        yaccValue = ":";
        return Tokens.tSYMBEG;
    }

    private int comma(int c) {
        setState(EXPR_BEG|EXPR_LABEL);
        yaccValue = ",";

        return c;
    }

    private int doKeyword(int state) {
        int leftParenBegin = getLeftParenBegin();
        if (leftParenBegin > 0 && leftParenBegin == parenNest) {
            setLeftParenBegin(0);
            parenNest--;
            return Tokens.kDO_LAMBDA;
        }

        if (conditionState.isInState()) return Tokens.kDO_COND;

        if (cmdArgumentState.isInState() && !isLexState(state, EXPR_CMDARG)) {
            return Tokens.kDO_BLOCK;
        }
        if (isLexState(state,  EXPR_BEG|EXPR_ENDARG)) {
            return Tokens.kDO_BLOCK;
        }
        return Tokens.kDO;
    }

    @SuppressWarnings("fallthrough")
    private int dollar() {
        setState(EXPR_END);
        newtok(true);
        int c = nextc();

        switch (c) {
        case '_':       /* $_: last read line string */
            c = nextc();
            if (isIdentifierChar(c)) {
                if (!tokadd_ident(c)) return EOF;

                last_state = lex_state;
                setState(EXPR_END);
                yaccValue = createTokenString().intern();
                return Tokens.tGVAR;
            }
            pushback(c);
            c = '_';
            // fall through
        case '~':       /* $~: match-data */
        case '*':       /* $*: argv */
        case '$':       /* $$: pid */
        case '?':       /* $?: last status */
        case '!':       /* $!: error string */
        case '@':       /* $@: error position */
        case '/':       /* $/: input record separator */
        case '\\':      /* $\: output record separator */
        case ';':       /* $;: field separator */
        case ',':       /* $,: output field separator */
        case '.':       /* $.: last read line number */
        case '=':       /* $=: ignorecase */
        case ':':       /* $:: load path */
        case '<':       /* $<: reading filename */
        case '>':       /* $>: default output handle */
        case '\"':      /* $": already loaded files */
            yaccValue = "$" + (char) c;
            return Tokens.tGVAR;

        case '-':
            c = nextc();
            if (isIdentifierChar(c)) {
                if (!tokadd_mbchar(c)) return EOF;
            } else {
                pushback(c);
                pushback('-');
                return '$';
            }
            yaccValue = createTokenString().intern();
            /* xxx shouldn't check if valid option variable */
            return Tokens.tGVAR;

        case '&':       /* $&: last match */
        case '`':       /* $`: string before last match */
        case '\'':      /* $': string after last match */
        case '+':       /* $+: string matches last paren. */
            // Explicit reference to these vars as symbols...
            if (isLexState(last_state, EXPR_FNAME)) {
                yaccValue = "$" + (char) c;
                return Tokens.tGVAR;
            }

            yaccValue = new BackRefParseNode(getPosition(), c);
            return Tokens.tBACK_REF;

        case '1': case '2': case '3': case '4': case '5': case '6':
        case '7': case '8': case '9':
            do {
                c = nextc();
            } while (Character.isDigit(c));
            pushback(c);
            if (isLexState(last_state, EXPR_FNAME)) {
                yaccValue = createTokenString().intern();
                return Tokens.tGVAR;
            }

            int ref;
            String refAsString = createTokenString();

            try {
                ref = Integer.parseInt(refAsString.substring(1).intern());
            } catch (NumberFormatException e) {
                warnings.warn(RubyWarnings.ID.AMBIGUOUS_ARGUMENT, "`" + refAsString + "' is too big for a number variable, always nil");
                ref = 0;
            }

            yaccValue = new NthRefParseNode(getPosition(), ref);
            return Tokens.tNTH_REF;
        case '0':
            setState(EXPR_END);

            return identifierToken(Tokens.tGVAR, ("$" + (char) c).intern());
        default:
            if (!isIdentifierChar(c)) {
                if (c == EOF || Character.isSpaceChar(c)) {
                    compile_error(SyntaxException.PID.CVAR_BAD_NAME, "`$' without identifiers is not allowed as a global variable name");
                } else {
                    pushback(c);
                    compile_error(SyntaxException.PID.CVAR_BAD_NAME, "`$" + ((char) c) + "' is not allowed as a global variable name");
                }
            }

            last_state = lex_state;
            setState(EXPR_END);

            tokadd_ident(c);

            return identifierToken(Tokens.tGVAR, createTokenString().intern());  // $blah
        }
    }

    private int dot() {
        int c;

        setState(EXPR_BEG);
        if ((c = nextc()) == '.') {
            if ((c = nextc()) == '.') {
                yaccValue = "...";
                return Tokens.tDOT3;
            }
            pushback(c);
            yaccValue = "..";
            return Tokens.tDOT2;
        }

        pushback(c);
        if (Character.isDigit(c)) compile_error(SyntaxException.PID.FLOAT_MISSING_ZERO, "no .<digit> floating literal anymore; put 0 before dot");

        setState(EXPR_DOT);
        yaccValue = ".";
        return Tokens.tDOT;
    }

    private int doubleQuote(boolean commandState) {
        int label = isLabelPossible(commandState) ? str_label : 0;
        lex_strterm = new StringTerm(str_dquote|label, '\0', '"');
        yaccValue = "\"";

        return Tokens.tSTRING_BEG;
    }

    private int greaterThan() {
        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        int c = nextc();

        switch (c) {
        case '=':
            yaccValue = ">=";

            return Tokens.tGEQ;
        case '>':
            if ((c = nextc()) == '=') {
                setState(EXPR_BEG);
                yaccValue = ">>";
                return Tokens.tOP_ASGN;
            }
            pushback(c);

            yaccValue = ">>";
            return Tokens.tRSHFT;
        default:
            pushback(c);
            yaccValue = ">";
            return Tokens.tGT;
        }
    }

    private int identifier(int c, boolean commandState) {
        if (!isIdentifierChar(c)) {
            String badChar = "\\" + Integer.toOctalString(c & 0xff);
            compile_error(SyntaxException.PID.CHARACTER_BAD, "Invalid char `" + badChar + "' ('" + (char) c + "') in expression");
        }

        newtok(true);
        do {
            if (!tokadd_mbchar(c)) return EOF;
            c = nextc();
        } while (isIdentifierChar(c));

        boolean lastBangOrPredicate = false;

        // methods 'foo!' and 'foo?' are possible but if followed by '=' it is relop
        if (c == '!' || c == '?') {
            if (!peek('=')) {
                lastBangOrPredicate = true;
            } else {
                pushback(c);
            }
        } else {
            pushback(c);
        }

        int result = 0;

        last_state = lex_state;
        String tempVal;
        if (lastBangOrPredicate) {
            result = Tokens.tFID;
            tempVal = createTokenString();
        } else {
            if (isLexState(lex_state, EXPR_FNAME)) {
                if ((c = nextc()) == '=') {
                    int c2 = nextc();

                    if (c2 != '~' && c2 != '>' &&
                            (c2 != '=' || peek('>'))) {
                        result = Tokens.tIDENTIFIER;
                        pushback(c2);
                    } else {
                        pushback(c2);
                        pushback(c);
                    }
                } else {
                    pushback(c);
                }
            }
            tempVal = createTokenString();

            if (result == 0 && Character.isUpperCase(tempVal.charAt(0))) {
                result = Tokens.tCONSTANT;
            } else {
                result = Tokens.tIDENTIFIER;
            }
        }

        if (isLabelPossible(commandState)) {
            if (isLabelSuffix()) {
                setState(EXPR_ARG|EXPR_LABELED);
                nextc();
                yaccValue = tempVal.intern();
                return Tokens.tLABEL;
            }
        }

        if (lex_state != EXPR_DOT) {
            Keyword keyword = getKeyword(tempVal); // Is it is a keyword?

            if (keyword != null) {
                int state = lex_state; // Save state at time keyword is encountered
                setState(keyword.state);

                if (isLexState(state, EXPR_FNAME)) {
                    yaccValue = keyword.name;
                    return keyword.id0;
                } else {
                    yaccValue = getPosition();
                }

                if (isLexState(lex_state, EXPR_BEG)) commandStart = true;

                if (keyword.id0 == Tokens.kDO) return doKeyword(state);

                if (isLexState(state, EXPR_BEG|EXPR_LABELED)) {
                    return keyword.id0;
                } else {
                    if (keyword.id0 != keyword.id1) setState(EXPR_BEG|EXPR_LABEL);
                    return keyword.id1;
                }
            }
        }

        if (isLexState(lex_state, EXPR_BEG_ANY|EXPR_ARG_ANY|EXPR_DOT)) {
            setState(commandState ? EXPR_CMDARG : EXPR_ARG);
        } else if (lex_state == EXPR_FNAME) {
            setState(EXPR_ENDFN);
        } else {
            setState(EXPR_END);
        }

        tempVal = tempVal.intern();

        if (tempVal.equals("function") && parserSupport.getContext() != null && parserSupport.getContext().getOptions().INLINE_JS) {
            return javaScript(tempVal);
        }

        return identifierToken(result, tempVal);
    }

    private int javaScript(String keyword) {
        if (!Character.isWhitespace(p(lex_p))) {
            return identifierToken(Tokens.tIDENTIFIER, keyword);
        }

        int length = 0;

        while (Character.isWhitespace(p(lex_p + length))) {
            length++;
        }

        if (!Character.isJavaIdentifierPart(p(lex_p + length))) {
            return identifierToken(Tokens.tIDENTIFIER, keyword);
        }

        length++;

        while (Character.isJavaIdentifierPart(p(lex_p + length))) {
            length++;
        }

        if (p(lex_p + length) != '(') {
            return identifierToken(Tokens.tIDENTIFIER, keyword);
        }

        length++;

        // Commit to parsing this as JavaScript

        // TODO CS 11-09-16 strings, escaping etc

        while (p(lex_p + length) != '{') {
            length++;
        }

        length++;

        int depth = 0;

        int c;

        while ((c = p(lex_p + length)) != '}' || depth > 0) {
            switch (c) {
                case '{':
                    depth++;
                    break;
                case '}':
                    depth--;
                    break;
            }

            length++;
        }

        length++;

        final StringBuilder builder = new StringBuilder();
        builder.append(keyword);

        for (int n = 0; n < length; n++) {
            builder.append((char) nextc());
        }

        yaccValue = builder.toString();
        return Tokens.tJAVASCRIPT;
    }

    private int leftBracket(boolean spaceSeen) {
        parenNest++;
        int c = '[';
        if (isAfterOperator()) {
            setState(EXPR_ARG);

            if ((c = nextc()) == ']') {
                if (peek('=')) {
                    nextc();
                    yaccValue = "[]=";
                    return Tokens.tASET;
                }
                yaccValue = "[]";
                return Tokens.tAREF;
            }
            pushback(c);
            setState(getState() | EXPR_LABEL);
            yaccValue = "[";
            return '[';
        } else if (isBEG() || (isARG() && (spaceSeen || isLexState(lex_state, EXPR_LABELED)))) {
            c = Tokens.tLBRACK;
        }

        setState(EXPR_BEG|EXPR_LABEL);
        conditionState.stop();
        cmdArgumentState.stop();
        yaccValue = "[";
        return c;
    }

    private int leftCurly() {
        braceNest++;
        int leftParenBegin = getLeftParenBegin();
        if (leftParenBegin > 0 && leftParenBegin == parenNest) {
            setState(EXPR_BEG);
            setLeftParenBegin(0);
            parenNest--;
            conditionState.stop();
            cmdArgumentState.stop();
            yaccValue = "{";
            return Tokens.tLAMBEG;
        }

        char c;
        if (isLexState(lex_state, EXPR_LABELED)) {
            c = Tokens.tLBRACE;
        } else if (isLexState(lex_state, EXPR_ARG_ANY|EXPR_END|EXPR_ENDFN)) { // block (primary)
            c = Tokens.tLCURLY;
        } else if (isLexState(lex_state, EXPR_ENDARG)) { // block (expr)
            c = Tokens.tLBRACE_ARG;
        } else { // hash
            c = Tokens.tLBRACE;
        }

        conditionState.stop();
        cmdArgumentState.stop();
        setState(EXPR_BEG);
        if (c != Tokens.tLBRACE_ARG) setState(getState() | EXPR_LABEL);
        if (c != Tokens.tLBRACE) commandStart = true;
        yaccValue = getPosition();

        return c;
    }

    private int leftParen(boolean spaceSeen) {
        int result;

        if (isBEG()) {
            result = Tokens.tLPAREN;
        } else if (isSpaceArg('(', spaceSeen)) {
            result = Tokens.tLPAREN_ARG;
        } else {
            result = Tokens.tLPAREN2;
        }

        parenNest++;
        conditionState.stop();
        cmdArgumentState.stop();
        setState(EXPR_BEG|EXPR_LABEL);

        yaccValue = getPosition();
        return result;
    }

    private int lessThan(boolean spaceSeen) {
        last_state = lex_state;
        int c = nextc();
        if (c == '<' && !isLexState(lex_state, EXPR_DOT|EXPR_CLASS) &&
                !isEND() && (!isARG() || isLexState(lex_state, EXPR_LABELED) || spaceSeen)) {
            int tok = hereDocumentIdentifier();

            if (tok != 0) return tok;
        }

        if (isAfterOperator()) {
            setState(EXPR_ARG);
        } else {
            if (isLexState(lex_state, EXPR_CLASS)) commandStart = true;
            setState(EXPR_BEG);
        }

        switch (c) {
        case '=':
            if ((c = nextc()) == '>') {
                yaccValue = "<=>";
                return Tokens.tCMP;
            }
            pushback(c);
            yaccValue = "<=";
            return Tokens.tLEQ;
        case '<':
            if ((c = nextc()) == '=') {
                setState(EXPR_BEG);
                yaccValue = "<<";
                return Tokens.tOP_ASGN;
            }
            pushback(c);
            yaccValue = "<<";
            warn_balanced(c, spaceSeen, "<<", "here document");
            return Tokens.tLSHFT;
        default:
            yaccValue = "<";
            pushback(c);
            return Tokens.tLT;
        }
    }

    private int minus(boolean spaceSeen) {
        int c = nextc();

        if (isAfterOperator()) {
            setState(EXPR_ARG);
            if (c == '@') {
                yaccValue = "-@";
                return Tokens.tUMINUS;
            }
            pushback(c);
            yaccValue = "-";
            return Tokens.tMINUS;
        }
        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = "-";
            return Tokens.tOP_ASGN;
        }
        if (c == '>') {
            setState(EXPR_ENDFN);
            yaccValue = "->";
            return Tokens.tLAMBDA;
        }
        if (isBEG() || (isSpaceArg(c, spaceSeen) && arg_ambiguous())) {
            setState(EXPR_BEG);
            pushback(c);
            yaccValue = "-";
            if (Character.isDigit(c)) {
                return Tokens.tUMINUS_NUM;
            }
            return Tokens.tUMINUS;
        }
        setState(EXPR_BEG);
        pushback(c);
        yaccValue = "-";
        warn_balanced(c, spaceSeen, "-", "unary operator");
        return Tokens.tMINUS;
    }

    private int percent(boolean spaceSeen) {
        if (isBEG()) return parseQuote(nextc());

        int c = nextc();

        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = "%";
            return Tokens.tOP_ASGN;
        }

        if (isSpaceArg(c, spaceSeen)) return parseQuote(c);

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        pushback(c);
        yaccValue = "%";
        warn_balanced(c, spaceSeen, "%", "string literal");
        return Tokens.tPERCENT;
    }

    private int pipe() {
        int c = nextc();

        switch (c) {
        case '|':
            setState(EXPR_BEG);
            if ((c = nextc()) == '=') {
                setState(EXPR_BEG);
                yaccValue = "||";
                return Tokens.tOP_ASGN;
            }
            pushback(c);
            yaccValue = "||";
            return Tokens.tOROP;
        case '=':
            setState(EXPR_BEG);
            yaccValue = "|";
            return Tokens.tOP_ASGN;
        default:
            setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG|EXPR_LABEL);

            pushback(c);
            yaccValue = "|";
            return Tokens.tPIPE;
        }
    }

    private int plus(boolean spaceSeen) {
        int c = nextc();
        if (isAfterOperator()) {
            setState(EXPR_ARG);
            if (c == '@') {
                yaccValue = "+@";
                return Tokens.tUPLUS;
            }
            pushback(c);
            yaccValue = "+";
            return Tokens.tPLUS;
        }

        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = "+";
            return Tokens.tOP_ASGN;
        }

        if (isBEG() || (isSpaceArg(c, spaceSeen) && arg_ambiguous())) {
            setState(EXPR_BEG);
            pushback(c);
            if (Character.isDigit(c)) {
                c = '+';
                return parseNumber(c);
            }
            yaccValue = "+";
            return Tokens.tUPLUS;
        }

        setState(EXPR_BEG);
        pushback(c);
        yaccValue = "+";
        warn_balanced(c, spaceSeen, "+", "unary operator");
        return Tokens.tPLUS;
    }

    private int questionMark() {
        int c;

        if (isEND()) {
            setState(EXPR_VALUE);
            yaccValue = "?";
            return '?';
        }

        c = nextc();
        if (c == EOF) compile_error(SyntaxException.PID.INCOMPLETE_CHAR_SYNTAX, "incomplete character syntax");

        if (Character.isWhitespace(c)){
            if (!isARG()) {
                int c2 = 0;
                switch (c) {
                case ' ':
                    c2 = 's';
                    break;
                case '\n':
                    c2 = 'n';
                    break;
                case '\t':
                    c2 = 't';
                    break;
                        /* What is \v in C?
                    case '\v':
                        c2 = 'v';
                        break;
                        */
                case '\r':
                    c2 = 'r';
                    break;
                case '\f':
                    c2 = 'f';
                    break;
                }
                if (c2 != 0) {
                    warnings.warn(RubyWarnings.ID.INVALID_CHAR_SEQUENCE, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "invalid character syntax; use ?\\" + c2);
                }
            }
            pushback(c);
            setState(EXPR_VALUE);
            yaccValue = "?";
            return '?';
        }

        if (!isASCII(c)) {
            if (!tokadd_mbchar(c)) return EOF;
        } else if (isIdentifierChar(c) && !peek('\n') && isNext_identchar()) {
            newtok(true);
            pushback(c);
            setState(EXPR_VALUE);
            yaccValue = "?";
            return '?';
        } else if (c == '\\') {
            if (peek('u')) {
                nextc(); // Eat 'u'
                RopeBuilder oneCharBL = new RopeBuilder();
                oneCharBL.setEncoding(getEncoding());

                c = readUTFEscape(oneCharBL, false, false);

                if (c >= 0x80) {
                    tokaddmbc(c, oneCharBL);
                } else {
                    oneCharBL.append(c);
                }

                setState(EXPR_END);
                yaccValue = new StrParseNode(getPosition(), oneCharBL.toRope());

                return Tokens.tCHAR;
            } else {
                c = readEscape();
            }
        } else {
            newtok(true);
        }

        RopeBuilder oneCharBL = new RopeBuilder();
        oneCharBL.append(c);
        yaccValue = new StrParseNode(getPosition(), oneCharBL.toRope());
        setState(EXPR_END);
        return Tokens.tCHAR;
    }

    private int rightBracket() {
        parenNest--;
        conditionState.restart();
        cmdArgumentState.restart();
        setState(EXPR_ENDARG);
        yaccValue = "]";
        return Tokens.tRBRACK;
    }

    private int rightCurly() {
        conditionState.restart();
        cmdArgumentState.restart();
        setState(EXPR_ENDARG);
        yaccValue = "}";
        int tok = braceNest == 0 ? Tokens.tSTRING_DEND : Tokens.tRCURLY;
        braceNest--;
        return tok;
    }

    private int rightParen() {
        parenNest--;
        conditionState.restart();
        cmdArgumentState.restart();
        setState(EXPR_ENDFN);
        yaccValue = ")";
        return Tokens.tRPAREN;
    }

    private int singleQuote(boolean commandState) {
        int label = isLabelPossible(commandState) ? str_label : 0;
        lex_strterm = new StringTerm(str_squote|label, '\0', '\'');
        yaccValue = "'";

        return Tokens.tSTRING_BEG;
    }

    private int slash(boolean spaceSeen) {
        if (isBEG()) {
            lex_strterm = new StringTerm(str_regexp, '\0', '/');
            yaccValue = "/";
            return Tokens.tREGEXP_BEG;
        }
        
        int c = nextc();
        
        if (c == '=') {
            setState(EXPR_BEG);
            yaccValue = "/";
            return Tokens.tOP_ASGN;
        }
        pushback(c);
        if (isSpaceArg(c, spaceSeen)) {
            arg_ambiguous();
            lex_strterm = new StringTerm(str_regexp, '\0', '/');
            yaccValue = "/";
            return Tokens.tREGEXP_BEG;
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);

        yaccValue = "/";
        warn_balanced(c, spaceSeen, "/", "regexp literal");
        return Tokens.tDIVIDE;
    }

    private int star(boolean spaceSeen) {
        int c = nextc();
        
        switch (c) {
        case '*':
            if ((c = nextc()) == '=') {
                setState(EXPR_BEG);
                yaccValue = "**";
                return Tokens.tOP_ASGN;
            }

            pushback(c); // not a '=' put it back
            yaccValue = "**";

            if (isSpaceArg(c, spaceSeen)) {
                if (warnings.isVerbose())
                    warnings.warning(RubyWarnings.ID.ARGUMENT_AS_PREFIX, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "`**' interpreted as argument prefix");
                c = Tokens.tDSTAR;
            } else if (isBEG()) {
                c = Tokens.tDSTAR;
            } else {
                warn_balanced(c, spaceSeen, "**", "argument prefix");
                c = Tokens.tPOW;
            }
            break;
        case '=':
            setState(EXPR_BEG);
            yaccValue = "*";
            return Tokens.tOP_ASGN;
        default:
            pushback(c);
            if (isSpaceArg(c, spaceSeen)) {
                if (warnings.isVerbose() && !parserSupport.skipTruffleRubiniusWarnings(this))
                    warnings.warning(RubyWarnings.ID.ARGUMENT_AS_PREFIX, getFile(), getPosition().toSourceSection(src.getSource()).getStartLine(), "`*' interpreted as argument prefix");
                c = Tokens.tSTAR;
            } else if (isBEG()) {
                c = Tokens.tSTAR;
            } else {
                warn_balanced(c, spaceSeen, "*", "argument prefix");
                c = Tokens.tSTAR2;
            }
            yaccValue = "*";
        }

        setState(isAfterOperator() ? EXPR_ARG : EXPR_BEG);
        return c;
    }

    private int tilde() {
        int c;
        
        if (isAfterOperator()) {
            if ((c = nextc()) != '@') pushback(c);
            setState(EXPR_ARG);
        } else {
            setState(EXPR_BEG);
        }
        
        yaccValue = "~";
        return Tokens.tTILDE;
    }

    private ByteArrayBuilder numberBuffer = new ByteArrayBuilder(); // ascii is good enough.
    /**
     *  Parse a number from the input stream.
     *
     *@param c The first character of the number.
     *@return A int constant wich represents a token.
     */
    @SuppressWarnings("fallthrough")
    private int parseNumber(int c) {
        setState(EXPR_END);
        newtok(true);

        numberBuffer.clear();

        if (c == '-') {
        	numberBuffer.append((char) c);
            c = nextc();
        } else if (c == '+') {
        	// We don't append '+' since Java number parser gets confused
            c = nextc();
        }
        
        int nondigit = 0;

        if (c == '0') {
            int startLen = numberBuffer.getLength();

            switch (c = nextc()) {
                case 'x' :
                case 'X' : //  hexadecimal
                    c = nextc();
                    if (isHexChar(c)) {
                        for (;; c = nextc()) {
                            if (c == '_') {
                                if (nondigit != '\0') break;
                                nondigit = c;
                            } else if (isHexChar(c)) {
                                nondigit = '\0';
                                numberBuffer.append((char) c);
                            } else {
                                break;
                            }
                        }
                    }
                    pushback(c);

                    if (numberBuffer.getLength() == startLen) {
                        compile_error(SyntaxException.PID.BAD_HEX_NUMBER, "Hexadecimal number without hex-digits.");
                    } else if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    }
                    return getIntegerToken(numberBuffer.toString(), 16, numberLiteralSuffix(SUFFIX_ALL));
                case 'b' :
                case 'B' : // binary
                    c = nextc();
                    if (c == '0' || c == '1') {
                        for (;; c = nextc()) {
                            if (c == '_') {
                                if (nondigit != '\0') break;
								nondigit = c;
                            } else if (c == '0' || c == '1') {
                                nondigit = '\0';
                                numberBuffer.append((char) c);
                            } else {
                                break;
                            }
                        }
                    }
                    pushback(c);

                    if (numberBuffer.getLength() == startLen) {
                        compile_error(SyntaxException.PID.EMPTY_BINARY_NUMBER, "Binary number without digits.");
                    } else if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    }
                    return getIntegerToken(numberBuffer.toString(), 2, numberLiteralSuffix(SUFFIX_ALL));
                case 'd' :
                case 'D' : // decimal
                    c = nextc();
                    if (Character.isDigit(c)) {
                        for (;; c = nextc()) {
                            if (c == '_') {
                                if (nondigit != '\0') break;
								nondigit = c;
                            } else if (Character.isDigit(c)) {
                                nondigit = '\0';
                                numberBuffer.append((char) c);
                            } else {
                                break;
                            }
                        }
                    }
                    pushback(c);

                    if (numberBuffer.getLength() == startLen) {
                        compile_error(SyntaxException.PID.EMPTY_BINARY_NUMBER, "Binary number without digits.");
                    } else if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    }
                    return getIntegerToken(numberBuffer.toString(), 10, numberLiteralSuffix(SUFFIX_ALL));
                case 'o':
                case 'O':
                    c = nextc();
                case '0': case '1': case '2': case '3': case '4': //Octal
                case '5': case '6': case '7': case '_': 
                    for (;; c = nextc()) {
                        if (c == '_') {
                            if (nondigit != '\0') break;

							nondigit = c;
                        } else if (c >= '0' && c <= '7') {
                            nondigit = '\0';
                            numberBuffer.append((char) c);
                        } else {
                            break;
                        }
                    }
                    if (numberBuffer.getLength() > startLen) {
                        pushback(c);

                        if (nondigit != '\0') compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");

                        return getIntegerToken(numberBuffer.toString(), 8, numberLiteralSuffix(SUFFIX_ALL));
                    }
                case '8' :
                case '9' :
                    compile_error(SyntaxException.PID.BAD_OCTAL_DIGIT, "Illegal octal digit.");
                case '.' :
                case 'e' :
                case 'E' :
                	numberBuffer.append('0');
                    break;
                default :
                    pushback(c);
                    numberBuffer.append('0');
                    return getIntegerToken(numberBuffer.toString(), 10, numberLiteralSuffix(SUFFIX_ALL));
            }
        }

        boolean seen_point = false;
        boolean seen_e = false;

        for (;; c = nextc()) {
            switch (c) {
                case '0' :
                case '1' :
                case '2' :
                case '3' :
                case '4' :
                case '5' :
                case '6' :
                case '7' :
                case '8' :
                case '9' :
                    nondigit = '\0';
                    numberBuffer.append((char) c);
                    break;
                case '.' :
                    if (nondigit != '\0') {
                        pushback(c);
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    } else if (seen_point || seen_e) {
                        pushback(c);
                        return getNumberToken(numberBuffer.toString(), seen_e, seen_point, nondigit);
                    } else {
                    	int c2;
                        if (!Character.isDigit(c2 = nextc())) {
                            pushback(c2);
                        	pushback('.');
                            if (c == '_') { 
                            		// Enebo:  c can never be antrhign but '.'
                            		// Why did I put this here?
                            } else {
                                return getIntegerToken(numberBuffer.toString(), 10, numberLiteralSuffix(SUFFIX_ALL));
                            }
                        } else {
                            numberBuffer.append('.');
                            numberBuffer.append((char) c2);
                            seen_point = true;
                            nondigit = '\0';
                        }
                    }
                    break;
                case 'e' :
                case 'E' :
                    if (nondigit != '\0') {
                        compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    } else if (seen_e) {
                        pushback(c);
                        return getNumberToken(numberBuffer.toString(), seen_e, seen_point, nondigit);
                    } else {
                        numberBuffer.append((char) c);
                        seen_e = true;
                        nondigit = c;
                        c = nextc();
                        if (c == '-' || c == '+') {
                            numberBuffer.append((char) c);
                            nondigit = c;
                        } else {
                            pushback(c);
                        }
                    }
                    break;
                case '_' : //  '_' in number just ignored
                    if (nondigit != '\0') compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
                    nondigit = c;
                    break;
                default :
                    pushback(c);
                    return getNumberToken(numberBuffer.toString(), seen_e, seen_point, nondigit);
            }
        }
    }

    private int getNumberToken(String number, boolean seen_e, boolean seen_point, int nondigit) {
        boolean isFloat = seen_e || seen_point;
        if (nondigit != '\0') {
            compile_error(SyntaxException.PID.TRAILING_UNDERSCORE_IN_NUMBER, "Trailing '_' in number.");
        } else if (isFloat) {
            int suffix = numberLiteralSuffix(seen_e ? SUFFIX_I : SUFFIX_ALL);
            return getFloatToken(number, suffix);
        }
        return getIntegerToken(number, 10, numberLiteralSuffix(SUFFIX_ALL));
    }

    // Note: parser_tokadd_utf8 variant just for regexp literal parsing.  This variant is to be
    // called when string_literal and regexp_literal.
    public void readUTFEscapeRegexpLiteral(RopeBuilder buffer) {
        buffer.append('\\');
        buffer.append('u');

        if (peek('{')) { // handle \\u{...}
            do {
                buffer.append(nextc());
                if (scanHexLiteral(buffer, 6, false, "invalid Unicode escape") > 0x10ffff) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "invalid Unicode codepoint (too large)");
                }
            } while (peek(' ') || peek('\t'));

            int c = nextc();
            if (c != '}') compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX,  "unterminated Unicode escape");

            buffer.append((char) c);
        } else { // handle \\uxxxx
            scanHexLiteral(buffer, 4, true, "Invalid Unicode escape");
        }
    }

    // MRI: parser_tokadd_utf8 sans regexp literal parsing
    public int readUTFEscape(RopeBuilder buffer, boolean stringLiteral, boolean symbolLiteral) {
        int codepoint;
        int c;

        if (peek('{')) { // handle \\u{...}
            do {
                nextc(); // Eat curly or whitespace
                codepoint = scanHex(6, false, "invalid Unicode escape");
                if (codepoint > 0x10ffff) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX,  "invalid Unicode codepoint (too large)");
                }
                if (buffer != null) readUTF8EscapeIntoBuffer(codepoint, buffer, stringLiteral);
            } while (peek(' ') || peek('\t'));

            c = nextc();
            if (c != '}') {
                compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "unterminated Unicode escape");
            }
        } else { // handle \\uxxxx
            codepoint = scanHex(4, true, "Invalid Unicode escape");
            if (buffer != null) readUTF8EscapeIntoBuffer(codepoint, buffer, stringLiteral);
        }

        return codepoint;
    }
    
    private void readUTF8EscapeIntoBuffer(int codepoint, RopeBuilder buffer, boolean stringLiteral) {
        if (codepoint >= 0x80) {
            buffer.setEncoding(UTF8_ENCODING);
            if (stringLiteral) tokaddmbc(codepoint, buffer);
        } else if (stringLiteral) {
            buffer.append((char) codepoint);
        }
    }

    @SuppressWarnings("fallthrough")
    public int readEscape() {
        int c = nextc();

        switch (c) {
            case '\\' : // backslash
                return c;
            case 'n' : // newline
                return '\n';
            case 't' : // horizontal tab
                return '\t';
            case 'r' : // carriage return
                return '\r';
            case 'f' : // form feed
                return '\f';
            case 'v' : // vertical tab
                return '\u000B';
            case 'a' : // alarm(bell)
                return '\u0007';
            case 'e' : // escape
                return '\u001B';
            case '0' : case '1' : case '2' : case '3' : // octal constant
            case '4' : case '5' : case '6' : case '7' :
                pushback(c);
                return scanOct(3);
            case 'x' : // hex constant
                return scanHex(2, false, "Invalid escape character syntax");
            case 'b' : // backspace
                return '\010';
            case 's' : // space
                return ' ';
            case 'M' :
                if ((c = nextc()) != '-') {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                } else if ((c = nextc()) == '\\') {
                    return (char) (readEscape() | 0x80);
                } else if (c == EOF) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                } 
                return (char) ((c & 0xff) | 0x80);
            case 'C' :
                if (nextc() != '-') {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                }
            case 'c' :
                if ((c = nextc()) == '\\') {
                    c = readEscape();
                } else if (c == '?') {
                    return '\177';
                } else if (c == EOF) {
                    compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
                }
                return (char) (c & 0x9f);
            case EOF :
                compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, "Invalid escape character syntax");
            default :
                return c;
        }
    }

    /**
     * Read up to count hexadecimal digits and store those digits in a token numberBuffer.  If strict is
     * provided then count number of hex digits must be present. If no digits can be read a syntax
     * exception will be thrown.  This will also return the codepoint as a value so codepoint
     * ranges can be checked.
     */
    private char scanHexLiteral(RopeBuilder buffer, int count, boolean strict, String errorMessage) {
        int i = 0;
        char hexValue = '\0';

        for (; i < count; i++) {
            int h1 = nextc();

            if (!isHexChar(h1)) {
                pushback(h1);
                break;
            }

            buffer.append(h1);

            hexValue <<= 4;
            hexValue |= Integer.parseInt(String.valueOf((char) h1), 16) & 15;
        }

        // No hex value after the 'x'.
        if (i == 0 || strict && count != i) {
            compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, errorMessage);
        }

        return hexValue;
    }

    /**
     * Read up to count hexadecimal digits.  If strict is provided then count number of hex
     * digits must be present. If no digits can be read a syntax exception will be thrown.
     */
    private int scanHex(int count, boolean strict, String errorMessage) {
        int i = 0;
        int hexValue = '\0';

        for (; i < count; i++) {
            int h1 = nextc();

            if (!isHexChar(h1)) {
                pushback(h1);
                break;
            }

            hexValue <<= 4;
            hexValue |= Integer.parseInt("" + (char) h1, 16) & 15;
        }

        // No hex value after the 'x'.
        if (i == 0 || (strict && count != i)) compile_error(SyntaxException.PID.INVALID_ESCAPE_SYNTAX, errorMessage);

        return hexValue;
    }

    public static final int EXPR_BEG     = 1;
    public static final int EXPR_END     = 1<<1;
    public static final int EXPR_ENDARG  = 1<<2;
    public static final int EXPR_ENDFN   = 1<<3;
    public static final int EXPR_ARG     = 1<<4;
    public static final int EXPR_CMDARG  = 1<<5;
    public static final int EXPR_MID     = 1<<6;
    public static final int EXPR_FNAME   = 1<<7;
    public static final int EXPR_DOT     = 1<<8;
    public static final int EXPR_CLASS   = 1<<9;
    public static final int EXPR_LABEL   = 1<<10;
    public static final int EXPR_LABELED = 1<<11;
    public static final int EXPR_VALUE = EXPR_BEG;
    public static final int EXPR_BEG_ANY = EXPR_BEG | EXPR_MID | EXPR_CLASS;
    public static final int EXPR_ARG_ANY = EXPR_ARG | EXPR_CMDARG;
    public static final int EXPR_END_ANY = EXPR_END | EXPR_ENDARG | EXPR_ENDFN;

    protected int braceNest = 0;
    public boolean commandStart;
    protected StackState conditionState = new StackState();
    protected StackState cmdArgumentState = new StackState();
    private String current_arg;
    private Encoding current_enc;
    protected boolean __end__seen = false;
    public boolean eofp = false;
    protected boolean has_shebang = false;
    protected int heredoc_end = 0;
    protected int heredoc_indent = 0;
    protected int heredoc_line_indent = 0;
    public boolean inKwarg = false;
    protected int last_cr_line;
    protected int last_state;
    private int leftParenBegin = 0;
    public Rope lexb = null;
    public Rope lex_lastline = null;
    protected Rope lex_nextline = null;
    public int lex_p = 0;                  // Where current position is in current line
    protected int lex_pbeg = 0;
    public int lex_pend = 0;               // Where line ends
    protected int lex_state;
    protected int line_count = 0;
    protected int line_offset = 0;
    protected int parenNest = 0;
    protected int ruby_sourceline = 1;
    protected int ruby_sourceline_char_offset = 0;
    protected int ruby_sourceline_char_length = 0;
    protected LexerSource src;                // Stream of data that yylex() examines.
    protected int token;                      // Last token read via yylex().
    private CodeRange tokenCR;
    protected boolean tokenSeen = false;
    public SourceIndexLength tokline;
    private int ruby_sourceline_when_tokline_created;
    public int tokp = 0;                   // Where last token started
    protected Object yaccValue;               // Value of last token which had a value associated with it.

    public int column() {
        return tokp - lex_pbeg;
    }

    protected boolean comment_at_top() {
        int p = lex_pbeg;
        int pend = lex_p - 1;
        if (line_count != (has_shebang ? 2 : 1)) return false;
        while (p < pend) {
            if (!Character.isSpaceChar(p(p))) return false;
            p++;
        }
        return true;
    }

    public Rope createTokenByteArrayView() {
        return parserRopeOperations.makeShared(lexb, tokp, lex_p - tokp);
    }

    public String createTokenString(int start) {
        return RopeOperations.decodeRope(parserRopeOperations.makeShared(lexb, start, lex_p - start));
    }

    public String createTokenString() {
        return createTokenString(tokp);
    }

    protected Rope dedent_string(Rope string, int width) {
        int len = string.byteLength();
        int i, col = 0;

        for (i = 0; i < len && col < width; i++) {
            if (string.get(i) == ' ') {
                col++;
            } else if (string.get(i) == '\t') {
                int n = TAB_WIDTH * (col / TAB_WIDTH + 1);
                if (n > width) break;
                col = n;
            } else {
                break;
            }
        }

        return parserRopeOperations.makeShared(string, i, len - i);
    }

    protected void flush() {
        tokp = lex_p;
    }

    public int getBraceNest() {
        return braceNest;
    }

    public StackState getCmdArgumentState() {
        return cmdArgumentState;
    }

    public StackState getConditionState() {
        return conditionState;
    }

    public String getCurrentArg() {
        return current_arg;
    }

    public String getCurrentLine() {
        return RopeOperations.decodeRope(StandardCharsets.ISO_8859_1, lex_lastline);
    }

    public Encoding getEncoding() {
        return current_enc;
    }

    public String getFile() {
        return src.getSource().getName();
    }

    public int getHeredocIndent() {
        return heredoc_indent;
    }

    public int getLeftParenBegin() {
        return leftParenBegin;
    }

    public int getLineOffset() {
        return line_offset;
    }

    public int getState() {
        return lex_state;
    }

    public CodeRange getTokenCR() {
        return tokenCR;
    }

    public int incrementParenNest() {
        parenNest++;

        return parenNest;
    }

    public boolean isEndSeen() {
        return __end__seen;
    }

    public boolean isASCII(int c) {
        return Encoding.isMbcAscii((byte) c);
    }

    // FIXME: I added number gvars here and they did not.
    public boolean isGlobalCharPunct(int c) {
        switch (c) {
            case '_': case '~': case '*': case '$': case '?': case '!': case '@':
            case '/': case '\\': case ';': case ',': case '.': case '=': case ':':
            case '<': case '>': case '\"': case '-': case '&': case '`': case '\'':
            case '+': case '1': case '2': case '3': case '4': case '5': case '6':
            case '7': case '8': case '9': case '0':
                return true;
        }
        return isIdentifierChar(c);
    }

    /**
     * This is a valid character for an identifier?
     *
     * @param c is character to be compared
     * @return whether c is an identifier or not
     *
     * mri: is_identchar
     */
    public boolean isIdentifierChar(int c) {
        return c != EOF && (Character.isLetterOrDigit(c) || c == '_' || !isASCII(c));
    }

    public void lex_goto_eol() {
        lex_p = lex_pend;
    }

    protected void magicCommentEncoding(Rope encoding) {
        if (!comment_at_top()) return;

        setEncoding(encoding);
    }

    // FIXME: We significantly different from MRI in that we are just mucking
    // with lex_p pointers and not alloc'ing our own buffer (or using bytelist).
    // In most cases this does not matter much but for ripper or a place where
    // we remove actual source characters (like extra '"') then this acts differently.
    public void newtok(boolean unreadOnce) {
        tokline = getPosition();
        ruby_sourceline_when_tokline_created = ruby_sourceline;
        // We assume all idents are 7BIT until they aren't.
        tokenCR = CodeRange.CR_7BIT;

        tokp = lex_p - (unreadOnce ? 1 : 0); // We use tokp of ripper to mark beginning of tokens.
    }

    protected int numberLiteralSuffix(int mask) {
        int c = nextc();

        if (c == 'i') return (mask & SUFFIX_I) != 0 ?  mask & SUFFIX_I : 0;

        if (c == 'r') {
            int result = 0;
            if ((mask & SUFFIX_R) != 0) result |= (mask & SUFFIX_R);

            if (peek('i') && (mask & SUFFIX_I) != 0) {
                c = nextc();
                result |= (mask & SUFFIX_I);
            }

            return result;
        }
        if (c == '.') {
            int c2 = nextc();
            if (Character.isDigit(c2)) {
                compile_error("unexpected fraction part after numeric literal");
                do { // Ripper does not stop so we follow MRI here and read over next word...
                    c2 = nextc();
                } while (isIdentifierChar(c2));
            } else {
                pushback(c2);
            }
        }
        pushback(c);

        return 0;
    }

    public void parser_prepare() {
        int c = nextc();

        switch(c) {
            case '#':
                if (peek('!')) has_shebang = true;
                break;
            case 0xef:
                if (lex_pend - lex_p >= 2 && p(lex_p) == 0xbb && p(lex_p + 1) == 0xbf) {
                    setEncoding(UTF8_ENCODING);
                    lex_p += 2;
                    lex_pbeg = lex_p;
                    return;
                }
                break;
            case EOF:
                return;
        }
        pushback(c);

        current_enc = lex_lastline.getEncoding();
    }

    public int p(int offset) {
        return lexb.getBytes()[offset] & 0xff;
    }

    public boolean peek(int c) {
        return peek(c, 0);
    }

    protected boolean peek(int c, int n) {
        return lex_p+n < lex_pend && p(lex_p+n) == c;
    }

    public int precise_mbclen() {
        // A substring of a single-byte optimizable string is always single-byte optimizable, so there's no need
        // to actually perform the substring operation.
        if ((current_enc == lexb.getEncoding() && lexb.isSingleByteOptimizable()) || current_enc.isSingleByte()) {
            return 1;
        }

        // we subtract one since we have read past first byte by time we are calling this.
        int start = lex_p - 1;
        int end = lex_pend;
        int length = end - start;

        // Otherwise, take the substring and see if that new string is single-byte optimizable.
        Rope rope = parserRopeOperations.makeShared(lexb, start, length);
        if ((current_enc == rope.getEncoding() && rope.isSingleByteOptimizable())) {
            return 1;
        }

        // Barring all else, we must inspect the bytes for the substring.
        return StringSupport.encFastMBCLen(rope.getBytes(), 0, rope.byteLength(), current_enc);
    }

    public void pushback(int c) {
        if (c == -1) return;

        lex_p--;

        if (lex_p > lex_pbeg && p(lex_p) == '\n' && p(lex_p-1) == '\r') {
            lex_p--;
        }
    }

    public void superReset() {
        braceNest = 0;
        commandStart = true;
        heredoc_indent = 0;
        heredoc_line_indent = 0;
        last_cr_line = -1;
        parenNest = 0;
        ruby_sourceline = 1;
        // No updateLineOffset because it's about to be done anyway in the only caller of this method
        token = 0;
        tokenSeen = false;
        tokp = 0;
        yaccValue = null;

        setState(0);
        resetStacks();
    }

    public void resetStacks() {
        conditionState.reset();
        cmdArgumentState.reset();
    }

    protected char scanOct(int count) {
        char value = '\0';

        for (int i = 0; i < count; i++) {
            int c = nextc();

            if (!isOctChar(c)) {
                pushback(c);
                break;
            }

            value <<= 3;
            value |= Integer.parseInt(String.valueOf((char) c), 8);
        }

        return value;
    }

    public void setCurrentArg(String current_arg) {
        this.current_arg = current_arg;
    }

    // FIXME: This is icky.  Ripper is setting encoding immediately but in Parsers lexer we are not.
    public void setCurrentEncoding(Encoding encoding) {
        current_enc = encoding;
    }
    // FIXME: This is mucked up...current line knows it's own encoding so that must be changed.  but we also have two
    // other sources.  I am thinking current_enc should be removed in favor of src since it needs to know encoding to
    // provide next line.
    public void setEncoding(Encoding encoding) {
        setCurrentEncoding(encoding);
        src.setEncoding(encoding);
        lexb = parserRopeOperations.withEncoding(lexb, encoding);
    }

    protected void set_file_encoding(int str, int send) {
        boolean sep = false;
        for (;;) {
            if (send - str <= 6) return;

            switch(p(str+6)) {
                case 'C': case 'c': str += 6; continue;
                case 'O': case 'o': str += 5; continue;
                case 'D': case 'd': str += 4; continue;
                case 'I': case 'i': str += 3; continue;
                case 'N': case 'n': str += 2; continue;
                case 'G': case 'g': str += 1; continue;
                case '=': case ':':
                    sep = true;
                    str += 6;
                    break;
                default:
                    str += 6;
                    if (Character.isSpaceChar(p(str))) break;
                    continue;
            }
            if (RopeOperations.caseInsensitiveCmp(parserRopeOperations.makeShared(lexb, str - 6, 6), CODING) == 0) break;
        }

        for(;;) {
            do {
                str++;
                if (str >= send) return;
            } while(Character.isSpaceChar(p(str)));
            if (sep) break;

            if (p(str) != '=' && p(str) != ':') return;
            sep = true;
            str++;
        }

        int beg = str;
        while ((p(str) == '-' || p(str) == '_' || Character.isLetterOrDigit(p(str))) && ++str < send) {}
        setEncoding(parserRopeOperations.makeShared(lexb, beg, str - beg));
    }

    public void setHeredocLineIndent(int heredoc_line_indent) {
        this.heredoc_line_indent = heredoc_line_indent;
    }

    public void setHeredocIndent(int heredoc_indent) {
        this.heredoc_indent = heredoc_indent;
    }

    public void setBraceNest(int nest) {
        braceNest = nest;
    }

    public void setLeftParenBegin(int value) {
        leftParenBegin = value;
    }

    /**
     * Allow the parser to set the source for its lexer.
     *
     * @param source where the lexer gets raw data
     */
    public void setSource(LexerSource source) {
        this.src = source;
    }

    public void setState(int state) {
        this.lex_state = state;
    }

    public void setValue(Object yaccValue) {
        this.yaccValue = yaccValue;
    }

    protected boolean strncmp(Rope one, Rope two, int length) {
        if (one.byteLength() < length || two.byteLength() < length) return false;

        return parserRopeOperations.makeShared(one, 0, length).equals(parserRopeOperations.makeShared(two, 0, length));
    }

    public void tokAdd(int first_byte, RopeBuilder buffer) {
        buffer.append((byte) first_byte);
    }

    public void tokCopy(int length, RopeBuilder buffer) {
        buffer.append(parserRopeOperations.makeShared(lexb, lex_p - length, length));
    }

    public boolean tokadd_ident(int c) {
        do {
            if (!tokadd_mbchar(c)) return false;
            c = nextc();
        } while (isIdentifierChar(c));
        pushback(c);

        return true;
    }

    // mri: parser_tokadd_mbchar
    /**
     * This differs from MRI in a few ways.  This version does not apply value to a separate token buffer.
     * It is for use when we know we will not be omitting or including ant non-syntactical characters.  Use
     * tokadd_mbchar(int, ByteArrayView) if the string differs from actual source.  Secondly, this returns a boolean
     * instead of the first byte passed.  MRI only used the return value as a success/failure code to return
     * EOF.
     *
     * Because this version does not use a separate token buffer we only just increment lex_p.  When we reach
     * end of the token it will just get the bytes directly from source directly.
     */
    public boolean tokadd_mbchar(int first_byte) {
        int length = precise_mbclen();

        if (length <= 0) {
            compile_error("invalid multibyte char (" + getEncoding() + ")");
        } else if (length > 1) {
            tokenCR = CodeRange.CR_VALID;
        }

        lex_p += length - 1;  // we already read first byte so advance pointer for remainder

        return true;
    }

    // mri: parser_tokadd_mbchar
    public boolean tokadd_mbchar(int first_byte, RopeBuilder buffer) {
        int length = precise_mbclen();

        if (length <= 0) compile_error("invalid multibyte char (" + getEncoding() + ")");

        tokAdd(first_byte, buffer);                  // add first byte since we have it.
        lex_p += length - 1;                         // we already read first byte so advance pointer for remainder
        if (length > 1) tokCopy(length - 1, buffer); // copy next n bytes over.

        return true;
    }

    /**
     *  This looks deceptively like tokadd_mbchar(int, ByteArrayView) but it differs in that it uses
     *  the bytelists encoding and the first parameter is a full codepoint and not the first byte
     *  of a mbc sequence.
     */
    public void tokaddmbc(int codepoint, RopeBuilder buffer) {
        Encoding encoding = buffer.getEncoding();
        int length = encoding.codeToMbcLength(codepoint);
        final byte[] bytes = Arrays.copyOf(buffer.getBytes(), buffer.getLength() + length);
        encoding.codeToMbc(codepoint, bytes, buffer.getLength());
        buffer.clear();
        buffer.append(bytes);
    }

    /**
     * Last token read from the lexer at the end of a call to yylex()
     *
     * @return last token read
     */
    public int token() {
        return token;
    }

    public boolean update_heredoc_indent(int c) {
        if (heredoc_line_indent == -1) {
            if (c == '\n') heredoc_line_indent = 0;
        } else if (c == ' ') {
            heredoc_line_indent++;
            return true;
        } else if (c == '\t') {
            int w = (heredoc_line_indent / TAB_WIDTH) + 1;
            heredoc_line_indent = w * TAB_WIDTH;
            return true;
        } else if (c != '\n') {
            if (heredoc_indent > heredoc_line_indent) heredoc_indent = heredoc_line_indent;
            heredoc_line_indent = -1;
        }

        return false;
    }

    public void validateFormalIdentifier(String identifier) {
        char first = identifier.charAt(0);

        if (Character.isUpperCase(first)) {
            compile_error("formal argument cannot be a constant");
        }

        switch(first) {
            case '@':
                if (identifier.charAt(1) == '@') {
                    compile_error("formal argument cannot be a class variable");
                } else {
                    compile_error("formal argument cannot be an instance variable");
                }
                break;
            case '$':
                compile_error("formal argument cannot be a global variable");
                break;
            default:
                // This mechanism feels a tad dicey but at this point we are dealing with a valid
                // method name at least so we should not need to check the entire string...
                char last = identifier.charAt(identifier.length() - 1);

                if (last == '=' || last == '?' || last == '!') {
                    compile_error("formal argument must be local variable");
                }
        }
    }

    /**
     * Value of last token (if it is a token which has a value).
     *
     * @return value of last value-laden token
     */
    public Object value() {
        return yaccValue;
    }

    protected void warn_balanced(int c, boolean spaceSeen, String op, String syn) {
        if (!isLexState(last_state, EXPR_CLASS|EXPR_DOT|EXPR_FNAME|EXPR_ENDFN|EXPR_ENDARG) && spaceSeen && !Character.isWhitespace(c)) {
            ambiguousOperator(op, syn);
        }
    }

    public boolean was_bol() {
        return lex_p == lex_pbeg + 1;
    }

    public boolean whole_match_p(Rope eos, boolean indent) {
        int len = eos.byteLength();
        int p = lex_pbeg;

        if (indent) {
            for (int i = 0; i < lex_pend; i++) {
                if (!Character.isWhitespace(p(i+p))) {
                    p += i;
                    break;
                }
            }
        }
        int n = lex_pend - (p + len);
        if (n < 0) return false;
        if (n > 0 && p(p+len) != '\n') {
            if (p(p+len) != '\r') return false;
            if (n == 1 || p(p+len+1) != '\n') return false;
        }

        return strncmp(eos, parserRopeOperations.makeShared(lexb, p, len), len);
    }

    public static final int TAB_WIDTH = 8;

    // ruby constants for strings (should this be moved somewhere else?)
    public static final int STR_FUNC_ESCAPE=0x01;
    public static final int STR_FUNC_EXPAND=0x02;
    public static final int STR_FUNC_REGEXP=0x04;
    public static final int STR_FUNC_QWORDS=0x08;
    public static final int STR_FUNC_SYMBOL=0x10;
    // When the heredoc identifier specifies <<-EOF that indents before ident. are ok (the '-').
    public static final int STR_FUNC_INDENT=0x20;
    public static final int STR_FUNC_LABEL=0x40;

    public static final int str_label = STR_FUNC_LABEL;
    public static final int str_squote = 0;
    public static final int str_dquote = STR_FUNC_EXPAND;
    public static final int str_xquote = STR_FUNC_EXPAND;
    public static final int str_regexp = STR_FUNC_REGEXP | STR_FUNC_ESCAPE | STR_FUNC_EXPAND;
    public static final int str_ssym   = STR_FUNC_SYMBOL;
    public static final int str_dsym   = STR_FUNC_SYMBOL | STR_FUNC_EXPAND;

    public static final int EOF = -1; // 0 in MRI

    public static Rope END_MARKER = RopeOperations.create(new byte[]{'_', '_', 'E', 'N', 'D', '_', '_'}, ASCIIEncoding.INSTANCE, CR_7BIT);
    public static Rope BEGIN_DOC_MARKER = RopeOperations.create(new byte[]{'b', 'e', 'g', 'i', 'n'}, ASCIIEncoding.INSTANCE, CR_7BIT);
    public static Rope END_DOC_MARKER = RopeOperations.create(new byte[]{'e', 'n', 'd'}, ASCIIEncoding.INSTANCE, CR_7BIT);
    public static Rope CODING = RopeOperations.create(new byte[]{'c', 'o', 'd', 'i', 'n', 'g'}, ASCIIEncoding.INSTANCE, CR_7BIT);

    public static final Encoding UTF8_ENCODING = UTF8Encoding.INSTANCE;
    public static final Encoding USASCII_ENCODING = USASCIIEncoding.INSTANCE;
    public static final Encoding ASCII8BIT_ENCODING = ASCIIEncoding.INSTANCE;

    public static final int SUFFIX_R = 1<<0;
    public static final int SUFFIX_I = 1<<1;
    public static final int SUFFIX_ALL = 3;

    /**
     * @param c the character to test
     * @return true if character is a hex value (0-9a-f)
     */
    public static boolean isHexChar(int c) {
        return Character.isDigit(c) || ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F');
    }

    public static boolean isLexState(int state, int mask) {
        return (mask & state) != 0;
    }

    protected boolean isLexStateAll(int state, int mask) {
        return (mask & state) == mask;
    }

    protected boolean isARG() {
        return isLexState(lex_state, EXPR_ARG_ANY);
    }

    protected boolean isBEG() {
        return isLexState(lex_state, EXPR_BEG_ANY) || isLexStateAll(lex_state, EXPR_ARG|EXPR_LABELED);
    }

    protected boolean isEND() {
        return isLexState(lex_state, EXPR_END_ANY);
    }

    protected boolean isLabelPossible(boolean commandState) {
        return (isLexState(lex_state, EXPR_LABEL|EXPR_ENDFN) && !commandState) || isARG();
    }

    protected boolean isLabelSuffix() {
        return peek(':') && !peek(':', 1);
    }

    protected boolean isAfterOperator() {
        return isLexState(lex_state, EXPR_FNAME|EXPR_DOT);
    }

    protected boolean isNext_identchar() {
        int c = nextc();
        pushback(c);

        return c != EOF && (Character.isLetterOrDigit(c) || c == '_');
    }

    /**
     * @param c the character to test
     * @return true if character is an octal value (0-7)
     */
    public static boolean isOctChar(int c) {
        return '0' <= c && c <= '7';
    }

    protected boolean isSpaceArg(int c, boolean spaceSeen) {
        return isARG() && spaceSeen && !Character.isWhitespace(c);
    }

    /* MRI: magic_comment_marker */
    /* This impl is a little sucky.  We basically double scan the same bytelist twice.  Once here
     * and once in parseMagicComment.
     */
    public static int magicCommentMarker(Rope str, int begin) {
        int i = begin;
        int len = str.byteLength();

        final byte[] bytes = str.getBytes();
        while (i < len) {
            switch (bytes[i]) {
                case '-':
                    if (i >= 2 && bytes[i - 1] == '*' && bytes[i - 2] == '-') {
                        return i + 1;
                    }
                    i += 2;
                    break;
                case '*':
                    if (i + 1 >= len) {
                        return -1;
                    }

                    if (bytes[i + 1] != '-') {
                        i += 4;
                    } else if (bytes[i - 1] != '-') {
                        i += 2;
                    } else {
                        return i + 2;
                    }
                    break;
                default:
                    i += 3;
                    break;
            }
        }
        return -1;
    }

    public static final String magicString = "^[^\\S]*([^\\s\'\":;]+)\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|[^\"\\s;]+)[\\s;]*[^\\S]*$";
    public static final Regex magicRegexp = new Regex(magicString.getBytes(), 0, magicString.length(), 0, Encoding.load("ASCII"));


}