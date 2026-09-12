#ifndef DEVOURER_BRIDGE_JSON_H
#define DEVOURER_BRIDGE_JSON_H

/* Json — a small, dependency-free JSON value for the bridge control plane.
 *
 * Devourer emits JSON Lines (src/Event.h) but never parses any: nothing in the
 * library needs to read structured input. The bridge does — it takes requests —
 * so it needs a parser, and a flat-object field scanner (chanmig/JsonlLite.h)
 * is not enough once a request carries a nested frame or PHY description.
 *
 * This is the whole JSON value model and nothing more: no schema, no pointers,
 * no streaming. Requests are small and arrive one per line, so the parser is a
 * plain recursive descent over a std::string_view with a depth cap (a hostile
 * or buggy client must not blow the stack).
 *
 * Numbers are doubles with an int64 fast path, because the protocol carries
 * both channel numbers and TSF values: a TSF is a 64-bit microsecond counter
 * that a double silently rounds above 2^53, so integers keep their own slot
 * and serialize without a decimal point. */

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <initializer_list>
#include <map>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace bridge {

class Json {
public:
  enum class Type { Null, Bool, Int, Real, String, Array, Object };

  Json() = default;
  Json(std::nullptr_t) {}
  Json(bool b) : _type{Type::Bool}, _bool{b} {}
  Json(int v) : _type{Type::Int}, _int{v} {}
  Json(long v) : _type{Type::Int}, _int{v} {}
  Json(long long v) : _type{Type::Int}, _int{v} {}
  Json(unsigned v) : _type{Type::Int}, _int{static_cast<int64_t>(v)} {}
  Json(unsigned long v) : _type{Type::Int}, _int{static_cast<int64_t>(v)} {}
  Json(unsigned long long v) : _type{Type::Int}, _int{static_cast<int64_t>(v)} {}
  Json(double v) : _type{Type::Real}, _real{v} {}
  Json(const char *s) : _type{Type::String}, _str{s ? s : ""} {}
  Json(std::string s) : _type{Type::String}, _str{std::move(s)} {}
  Json(std::string_view s) : _type{Type::String}, _str{s} {}

  static Json array() {
    Json j;
    j._type = Type::Array;
    return j;
  }
  static Json object() {
    Json j;
    j._type = Type::Object;
    return j;
  }
  static Json array(std::initializer_list<Json> xs) {
    Json j = array();
    j._arr.assign(xs.begin(), xs.end());
    return j;
  }

  Type type() const { return _type; }
  bool is_null() const { return _type == Type::Null; }
  bool is_object() const { return _type == Type::Object; }
  bool is_array() const { return _type == Type::Array; }
  bool is_string() const { return _type == Type::String; }
  bool is_number() const { return _type == Type::Int || _type == Type::Real; }

  /* --- object/array building ---------------------------------------------
   * set() on a non-object promotes it, so `Json r; r.set("a",1)` works without
   * a ceremonial Json::object() at every call site. */
  Json &set(std::string key, Json v) {
    if (_type != Type::Object) {
      _type = Type::Object;
      _obj.clear();
    }
    _obj[std::move(key)] = std::move(v);
    return *this;
  }
  Json &push(Json v) {
    if (_type != Type::Array) {
      _type = Type::Array;
      _arr.clear();
    }
    _arr.push_back(std::move(v));
    return *this;
  }

  /* --- typed access with a caller-supplied default ------------------------
   * Absent, null and wrong-typed all yield the default. The bridge treats a
   * malformed field as a missing one and reports it at the op level, which
   * keeps every accessor total and the call sites free of error plumbing. */
  bool has(std::string_view key) const {
    return _type == Type::Object && _obj.count(std::string{key}) != 0;
  }
  const Json &at(std::string_view key) const {
    static const Json null_json;
    if (_type != Type::Object)
      return null_json;
    auto it = _obj.find(std::string{key});
    return it == _obj.end() ? null_json : it->second;
  }
  const std::vector<Json> &items() const { return _arr; }
  const std::map<std::string, Json> &fields() const { return _obj; }

  std::string str(std::string_view def = {}) const {
    return _type == Type::String ? _str : std::string{def};
  }
  int64_t integer(int64_t def = 0) const {
    if (_type == Type::Int)
      return _int;
    if (_type == Type::Real)
      return static_cast<int64_t>(_real);
    return def;
  }
  double real(double def = 0) const {
    if (_type == Type::Real)
      return _real;
    if (_type == Type::Int)
      return static_cast<double>(_int);
    return def;
  }
  bool boolean(bool def = false) const {
    return _type == Type::Bool ? _bool : def;
  }

  /* --- serialization ------------------------------------------------------ */
  std::string dump() const {
    std::string out;
    dump_into(out);
    return out;
  }

  void dump_into(std::string &out) const {
    switch (_type) {
    case Type::Null:
      out += "null";
      break;
    case Type::Bool:
      out += _bool ? "true" : "false";
      break;
    case Type::Int: {
      char buf[24];
      std::snprintf(buf, sizeof buf, "%lld", static_cast<long long>(_int));
      out += buf;
      break;
    }
    case Type::Real: {
      /* NaN/Inf have no JSON spelling; emit null rather than something no
       * parser accepts (same choice devourer's Event.h makes). */
      if (_real != _real || _real == HUGE_VAL || _real == -HUGE_VAL) {
        out += "null";
        break;
      }
      char buf[40];
      std::snprintf(buf, sizeof buf, "%.17g", _real);
      out += buf;
      break;
    }
    case Type::String:
      escape_into(_str, out);
      break;
    case Type::Array: {
      out += '[';
      bool first = true;
      for (const auto &v : _arr) {
        if (!first)
          out += ',';
        first = false;
        v.dump_into(out);
      }
      out += ']';
      break;
    }
    case Type::Object: {
      out += '{';
      bool first = true;
      for (const auto &kv : _obj) {
        if (!first)
          out += ',';
        first = false;
        escape_into(kv.first, out);
        out += ':';
        kv.second.dump_into(out);
      }
      out += '}';
      break;
    }
    }
  }

  /* --- parsing ------------------------------------------------------------
   * Returns false and leaves `err` set on malformed input. Trailing
   * non-whitespace after the value is an error: a line is exactly one value. */
  static bool parse(std::string_view in, Json &out, std::string &err) {
    size_t i = 0;
    Json v;
    if (!parse_value(in, i, 0, v, err))
      return false;
    skip_ws(in, i);
    if (i != in.size()) {
      err = "trailing content after JSON value";
      return false;
    }
    out = std::move(v);
    return true;
  }

private:
  static constexpr int kMaxDepth = 64;

  Type _type = Type::Null;
  bool _bool = false;
  int64_t _int = 0;
  double _real = 0;
  std::string _str;
  std::vector<Json> _arr;
  std::map<std::string, Json> _obj;

  static void escape_into(const std::string &s, std::string &out) {
    out += '"';
    for (unsigned char c : s) {
      switch (c) {
      case '"':
        out += "\\\"";
        break;
      case '\\':
        out += "\\\\";
        break;
      case '\n':
        out += "\\n";
        break;
      case '\r':
        out += "\\r";
        break;
      case '\t':
        out += "\\t";
        break;
      case '\b':
        out += "\\b";
        break;
      case '\f':
        out += "\\f";
        break;
      default:
        if (c < 0x20) {
          char buf[8];
          std::snprintf(buf, sizeof buf, "\\u%04x", c);
          out += buf;
        } else {
          out += static_cast<char>(c);
        }
      }
    }
    out += '"';
  }

  static void skip_ws(std::string_view in, size_t &i) {
    while (i < in.size() && (in[i] == ' ' || in[i] == '\t' || in[i] == '\n' ||
                             in[i] == '\r'))
      ++i;
  }

  static bool lit(std::string_view in, size_t &i, std::string_view want) {
    if (in.substr(i, want.size()) != want)
      return false;
    i += want.size();
    return true;
  }

  static void encode_utf8(uint32_t cp, std::string &out) {
    if (cp < 0x80) {
      out += static_cast<char>(cp);
    } else if (cp < 0x800) {
      out += static_cast<char>(0xC0 | (cp >> 6));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      out += static_cast<char>(0xE0 | (cp >> 12));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
      out += static_cast<char>(0xF0 | (cp >> 18));
      out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    }
  }

  static bool hex4(std::string_view in, size_t &i, uint32_t &out) {
    if (i + 4 > in.size())
      return false;
    uint32_t v = 0;
    for (int k = 0; k < 4; ++k) {
      const char c = in[i + k];
      v <<= 4;
      if (c >= '0' && c <= '9')
        v |= static_cast<uint32_t>(c - '0');
      else if (c >= 'a' && c <= 'f')
        v |= static_cast<uint32_t>(c - 'a' + 10);
      else if (c >= 'A' && c <= 'F')
        v |= static_cast<uint32_t>(c - 'A' + 10);
      else
        return false;
    }
    i += 4;
    out = v;
    return true;
  }

  static bool parse_string(std::string_view in, size_t &i, std::string &out,
                           std::string &err) {
    if (i >= in.size() || in[i] != '"') {
      err = "expected string";
      return false;
    }
    ++i;
    out.clear();
    while (i < in.size()) {
      const char c = in[i];
      if (c == '"') {
        ++i;
        return true;
      }
      if (c == '\\') {
        ++i;
        if (i >= in.size())
          break;
        switch (in[i]) {
        case '"':
          out += '"';
          ++i;
          break;
        case '\\':
          out += '\\';
          ++i;
          break;
        case '/':
          out += '/';
          ++i;
          break;
        case 'b':
          out += '\b';
          ++i;
          break;
        case 'f':
          out += '\f';
          ++i;
          break;
        case 'n':
          out += '\n';
          ++i;
          break;
        case 'r':
          out += '\r';
          ++i;
          break;
        case 't':
          out += '\t';
          ++i;
          break;
        case 'u': {
          ++i;
          uint32_t cp = 0;
          if (!hex4(in, i, cp)) {
            err = "bad \\u escape";
            return false;
          }
          /* A surrogate pair must be recombined before UTF-8 encoding, or the
           * result is CESU-8 and every strict consumer rejects it. */
          if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < in.size() &&
              in[i] == '\\' && in[i + 1] == 'u') {
            size_t save = i;
            i += 2;
            uint32_t lo = 0;
            if (hex4(in, i, lo) && lo >= 0xDC00 && lo <= 0xDFFF)
              cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
            else
              i = save;
          }
          encode_utf8(cp, out);
          break;
        }
        default:
          err = "unknown escape";
          return false;
        }
        continue;
      }
      if (static_cast<unsigned char>(c) < 0x20) {
        err = "raw control character in string";
        return false;
      }
      out += c;
      ++i;
    }
    err = "unterminated string";
    return false;
  }

  static bool parse_value(std::string_view in, size_t &i, int depth, Json &out,
                          std::string &err) {
    if (depth > kMaxDepth) {
      err = "nesting too deep";
      return false;
    }
    skip_ws(in, i);
    if (i >= in.size()) {
      err = "unexpected end of input";
      return false;
    }
    const char c = in[i];
    if (c == '{') {
      ++i;
      out = object();
      skip_ws(in, i);
      if (i < in.size() && in[i] == '}') {
        ++i;
        return true;
      }
      for (;;) {
        skip_ws(in, i);
        std::string key;
        if (!parse_string(in, i, key, err))
          return false;
        skip_ws(in, i);
        if (i >= in.size() || in[i] != ':') {
          err = "expected ':'";
          return false;
        }
        ++i;
        Json v;
        if (!parse_value(in, i, depth + 1, v, err))
          return false;
        out._obj[std::move(key)] = std::move(v);
        skip_ws(in, i);
        if (i < in.size() && in[i] == ',') {
          ++i;
          continue;
        }
        if (i < in.size() && in[i] == '}') {
          ++i;
          return true;
        }
        err = "expected ',' or '}'";
        return false;
      }
    }
    if (c == '[') {
      ++i;
      out = array();
      skip_ws(in, i);
      if (i < in.size() && in[i] == ']') {
        ++i;
        return true;
      }
      for (;;) {
        Json v;
        if (!parse_value(in, i, depth + 1, v, err))
          return false;
        out._arr.push_back(std::move(v));
        skip_ws(in, i);
        if (i < in.size() && in[i] == ',') {
          ++i;
          continue;
        }
        if (i < in.size() && in[i] == ']') {
          ++i;
          return true;
        }
        err = "expected ',' or ']'";
        return false;
      }
    }
    if (c == '"') {
      std::string s;
      if (!parse_string(in, i, s, err))
        return false;
      out = Json{std::move(s)};
      return true;
    }
    if (lit(in, i, "true")) {
      out = Json{true};
      return true;
    }
    if (lit(in, i, "false")) {
      out = Json{false};
      return true;
    }
    if (lit(in, i, "null")) {
      out = Json{};
      return true;
    }
    /* Number. Scan the JSON grammar's span, then decide int vs real on whether
     * a fraction or exponent appeared — so a TSF keeps all 64 bits. */
    const size_t start = i;
    if (i < in.size() && (in[i] == '-' || in[i] == '+'))
      ++i;
    bool any_digit = false, is_real = false;
    while (i < in.size()) {
      const char d = in[i];
      if (d >= '0' && d <= '9') {
        any_digit = true;
        ++i;
      } else if (d == '.' || d == 'e' || d == 'E') {
        is_real = true;
        ++i;
      } else if ((d == '-' || d == '+') && (in[i - 1] == 'e' || in[i - 1] == 'E')) {
        ++i;
      } else {
        break;
      }
    }
    if (!any_digit) {
      err = "unexpected token";
      return false;
    }
    const std::string num{in.substr(start, i - start)};
    if (is_real) {
      out = Json{std::strtod(num.c_str(), nullptr)};
    } else {
      errno = 0;
      const long long v = std::strtoll(num.c_str(), nullptr, 10);
      if (errno == ERANGE)
        out = Json{std::strtod(num.c_str(), nullptr)};
      else
        out = Json{v};
    }
    return true;
  }
};

} // namespace bridge

#endif /* DEVOURER_BRIDGE_JSON_H */
