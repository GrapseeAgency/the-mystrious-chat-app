import SwiftUI

/// Detail-sheet loader target (Identifiable for the red-packet sheet(item:)).
struct Wave7PacketTarget: Identifiable {
    let id: String
}

/// Fetches the packet detail then renders the sheet body (grab state included).
struct Wave7PacketDetailLoader: View {
    let packetId: String
    let api: PulseAPIClient
    @State private var detail: WireRedPacketDetail?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let detail {
                    ScrollView {
                        Wave7RedPacketDetailBody(detail: detail)
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("🧧 Red packet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task(id: packetId) {
                detail = try? await api.redPacketDetail(packetId)
            }
        }
    }
}
