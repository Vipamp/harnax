import Foundation
import SwiftUI

/// Runtime copy catalogue. Views read through `HXText` so a language picked inside the app repaints the
/// tree immediately, instead of waiting for a relaunch.
public final class HarnaxCatalog: ObservableObject, @unchecked Sendable {
    public static let shared = HarnaxCatalog()

    @Published public var language: HarnaxLanguage {
        didSet {
            UserDefaults.standard.set(language.rawValue, forKey: HarnaxLanguage.storageKey)
            cachedBundle = nil
        }
    }

    private var cachedBundle: Bundle?

    private init() {
        let stored = UserDefaults.standard.string(forKey: HarnaxLanguage.storageKey) ?? HarnaxLanguage.system.rawValue
        language = HarnaxLanguage(rawValue: stored) ?? .system
    }

    /// The lproj bundle for the picked language. Falls back to the SPM bundle, which follows the OS.
    private var bundle: Bundle {
        if let cachedBundle { return cachedBundle }
        let resolved: Bundle
        if let name = language.localeName,
           let path = Bundle.module.path(forResource: name, ofType: "lproj"),
           let localized = Bundle(path: path) {
            resolved = localized
        } else {
            resolved = Bundle.module
        }
        cachedBundle = resolved
        return resolved
    }

    private static let missing = #"￼__hx_missing__"#

    public func render(_ key: String, _ args: [any CVarArg]) -> String {
        let template = bundle.localizedString(forKey: key, value: Self.missing, table: nil)
        guard template != Self.missing else { return key }
        return args.isEmpty ? template : String(format: template, arguments: args)
    }
}

public func hx(_ key: String) -> String {
    HarnaxCatalog.shared.render(key, [])
}

public func hx(_ key: String, _ args: any CVarArg...) -> String {
    HarnaxCatalog.shared.render(key, args)
}

/// Localized text. Use this instead of `Text` — `Text("some.key")` treats its argument as a
/// `LocalizedStringKey` and looks it up in the app bundle, where library keys do not live.
public struct HXText: View {
    @ObservedObject private var catalog = HarnaxCatalog.shared
    private let key: String
    private let args: [any CVarArg]

    public init(_ key: String, _ args: any CVarArg...) {
        self.key = key
        self.args = args
    }

    public var body: some View {
        Text(verbatim: catalog.render(key, args))
    }
}
