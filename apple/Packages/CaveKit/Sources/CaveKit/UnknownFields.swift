import Foundation

/// A JSON value kept as read, to be written back unchanged.
/// Numbers are kept by value: whole numbers as integers, others as doubles.
public enum JSONValue: Hashable, Sendable, Codable {
    case null
    case bool(Bool)
    case integer(Int64)
    case unsigned(UInt64)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() {
            self = .null
        } else if let value = try? c.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? c.decode(Int64.self) {
            self = .integer(value)
        } else if let value = try? c.decode(UInt64.self) {
            self = .unsigned(value)
        } else if let value = try? c.decode(Double.self) {
            self = .double(value)
        } else if let value = try? c.decode(String.self) {
            self = .string(value)
        } else if let value = try? c.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try c.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let value): try c.encode(value)
        case .integer(let value): try c.encode(value)
        case .unsigned(let value): try c.encode(value)
        case .double(let value): try c.encode(value)
        case .string(let value): try c.encode(value)
        case .array(let value): try c.encode(value)
        case .object(let value): try c.encode(value)
        }
    }
}

/// The keys of a synced record that this version of Encave does not know, kept so that
/// writing the record back (after an edit, in a sync message, a file or a backup) does
/// not erase fields added by a newer version (docs/SYNC.md §1). Known keys always win:
/// a key the record knows is never kept here.
public struct UnknownFields: Hashable, Sendable {
    public var values: [String: JSONValue]

    public init(_ values: [String: JSONValue] = [:]) { self.values = values }

    public var isEmpty: Bool { values.isEmpty }

    /// These fields, or those of `stored` when there are none: an edit made from a form,
    /// which builds the record again from the fields it shows, keeps those of the stored record.
    func orKept(_ stored: UnknownFields?) -> UnknownFields { isEmpty ? stored ?? self : self }

    /// The keys of the object being decoded that are not in `known`.
    public init<Key: CodingKey & CaseIterable>(from decoder: Decoder, known: Key.Type) throws {
        let knownNames = Set(Key.allCases.map(\.stringValue))
        let c = try decoder.container(keyedBy: AnyCodingKey.self)
        var values: [String: JSONValue] = [:]
        for key in c.allKeys where !knownNames.contains(key.stringValue) {
            values[key.stringValue] = try c.decode(JSONValue.self, forKey: key)
        }
        self.values = values
    }

    /// Adds the kept keys to the object being encoded, except those in `known`; writes
    /// nothing when there are none, so records without unknown keys are encoded as before.
    public func encode<Key: CodingKey & CaseIterable>(to encoder: Encoder, known: Key.Type) throws {
        guard !values.isEmpty else { return }
        let knownNames = Set(Key.allCases.map(\.stringValue))
        var c = encoder.container(keyedBy: AnyCodingKey.self)
        for (key, value) in values where !knownNames.contains(key) { try c.encode(value, forKey: AnyCodingKey(key)) }
    }
}

/// Any key of a JSON object.
struct AnyCodingKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ string: String) { stringValue = string }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { nil }
}
