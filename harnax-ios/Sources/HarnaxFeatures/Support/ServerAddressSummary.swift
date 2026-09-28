import Foundation
import HarnaxKit

/// How an address is shown on a row: the host and port, without the scheme the user already knows.
public enum ServerAddressSummary {
    public static func hostPort(_ raw: String) -> String {
        guard let url = URL(string: raw), let host = url.host, !host.isEmpty else { return raw }
        guard let port = url.port else { return host }
        return "\(host):\(port)"
    }

    /// `管理接口 127.0.0.1:28080 · 路由接口 127.0.0.1:28081`, with the two labels from the catalogue.
    public static func line(admin: String, router: String) -> String {
        "\(hx("login.address.admin")) \(hostPort(admin)) · \(hx("login.address.router")) \(hostPort(router))"
    }
}
