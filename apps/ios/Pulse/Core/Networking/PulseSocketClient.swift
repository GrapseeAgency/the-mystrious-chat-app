import Foundation
import SocketIO

/// REAL Socket.IO relay client — mirrors Android PulseSocketClient and the
/// web use-pulse-socket.ts: connect → emit join → typed signal stream.
public final class PulseSocketClient {
    public enum Signal {
        case joined(onlineUserIds: [String])
        case presenceSnapshot(onlineUserIds: [String])
        case messageNew(conversationId: String, raw: [String: Any])
        /// N3-b — payload is { type, message (tombstoned), recipientIds, conversationId }.
        case messageDeleted(conversationId: String, raw: [String: Any])
        /// N3-b — payload is { type, message (fresh reactions), ... }.
        case messageReact(conversationId: String, raw: [String: Any])
        case messageRead(conversationId: String, userId: String)
        case typing(conversationId: String, userId: String, userName: String, isTyping: Bool)
        case voiceTranscript(roomId: String, speakerId: String, text: String)
        case callSignal(event: String, raw: [String: Any])
    }

    private let manager: SocketManager
    private let socket: SocketIOClient
    private let handlers: ArrayBuilder<()>

    public init(socketURL: URL) {
        manager = SocketManager(socketURL: socketURL, config: [.log(false), .reconnects(true)])
        socket = manager.defaultSocket
        handlers = .init()
    }

    public var signals: ((Signal) -> Void)?

    public func connect(userId: String) {
        socket.on(clientEvent: .connect) { [weak self] _, _ in
            self?.socket.emit("join", ["userId": userId])
        }
        socket.on("joined") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any],
                  let ids = obj["onlineUserIds"] as? [String] else { return }
            self?.signals?(.joined(onlineUserIds: ids))
        }
        socket.on("presence:snapshot") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any],
                  let ids = obj["onlineUserIds"] as? [String] else { return }
            self?.signals?(.presenceSnapshot(onlineUserIds: ids))
        }
        socket.on("message:new") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.messageNew(conversationId: obj["conversationId"] as? String ?? "", raw: obj))
        }
        socket.on("message:deleted") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.messageDeleted(conversationId: obj["conversationId"] as? String ?? "", raw: obj))
        }
        socket.on("message:react") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.messageReact(conversationId: obj["conversationId"] as? String ?? "", raw: obj))
        }
        socket.on("typing") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.typing(
                conversationId: obj["conversationId"] as? String ?? "",
                userId: obj["userId"] as? String ?? "",
                userName: obj["userName"] as? String ?? "",
                isTyping: obj["isTyping"] as? Bool ?? false,
            ))
        }
        socket.on("message:read") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.messageRead(
                conversationId: obj["conversationId"] as? String ?? "",
                userId: obj["userId"] as? String ?? "",
            ))
        }
        socket.on("voice:transcript") { [weak self] data, _ in
            guard let obj = data.first as? [String: Any] else { return }
            self?.signals?(.voiceTranscript(
                roomId: obj["conversationId"] as? String ?? "",
                speakerId: obj["userId"] as? String ?? "",
                text: obj["text"] as? String ?? "",
            ))
        }
        for event in ["call:offer", "call:answer", "call:ice", "call:cancel", "call:hangup"] {
            socket.on(event) { [weak self] data, _ in
                guard let obj = data.first as? [String: Any] else { return }
                self?.signals?(.callSignal(event: event, raw: obj))
            }
        }
        socket.connect()
    }

    public func emitTyping(recipients: [String], conversationId: String, userId: String, userName: String, isTyping: Bool) {
        socket.emit("typing", [
            "recipients": recipients,
            "conversationId": conversationId,
            "userId": userId,
            "userName": userName,
            "isTyping": isTyping,
        ])
    }

    public func disconnect() {
        socket.disconnect()
        socket.removeAllHandlers()
    }
}

/// Trivial sink so unused handler closures keep a reference without warnings.
final class ArrayBuilder<T> {
    var items: [T] = []
    func append(_ item: T) { items.append(item) }
}
