import SwiftUI
import WebRTC

// ─────────────────────────────────────────────────────────────
// Pulse — Wave R1-W2D REAL video renderers for the call overlay (web
// call-overlay.tsx parity):
//   • remote video → full-bleed MTK surface (web `absolute inset-0 cover`)
//   • local camera → mirrored PiP tile (web `scaleX(-1)`, ≈96×144pt rounded
//     bordered tile above the controls)
//
// Both wrap RTCMTLVideoView (Metal, the modern default) bound to a REAL
// RTCVideoTrack via add/remove sink. Renderer lifecycle: the sink detaches
// and the view releases with SwiftUI's dismantle — no camera surface leaks.
// ─────────────────────────────────────────────────────────────

/// One RTCVideoTrack rendered through RTCMTLVideoView. Recreated per track
/// instance; mirrored self-views flip the UIKit transform (web scaleX(-1)).
struct VideoTrackView: UIViewRepresentable {
    let track: RTCVideoTrack
    var mirrored: Bool = false

    final class Coordinator {
        let track: RTCVideoTrack
        init(track: RTCVideoTrack) { self.track = track }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(track: track)
    }

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView(frame: .zero)
        // Web object-cover parity — fill, crop, keep aspect.
        view.videoContentMode = .scaleAspectFill
        if mirrored {
            view.transform = CGAffineTransform(scaleX: -1, y: 1)
        }
        context.coordinator.track.add(view)
        return view
    }

    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {}

    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: Coordinator) {
        coordinator.track.remove(uiView)
    }
}

/// The peer's video, full-bleed behind the call UI (web remote <video>).
/// Renders only once the remote track actually arrives — the audio-only
/// fallback keeps the avatar-only layout.
struct RemoteVideoLayer: View {
    @ObservedObject var engine: PulseCallEngine

    var body: some View {
        ZStack {
            if let track = engine.remoteVideoTrack {
                VideoTrackView(track: track, mirrored: false)
                    .background(Color.black)
                    .ignoresSafeArea()
            }
        }
    }
}

/// The local camera PiP — mirrored self-view tile, bottom-trailing above the
/// controls (web `bottom-28 right-4` parity). Hidden while the camera toggle
/// is off (web cameraEnabled parity) or when no camera is attached.
struct LocalVideoPipLayer: View {
    @ObservedObject var engine: PulseCallEngine

    var body: some View {
        Group {
            if engine.cameraEnabled, let track = engine.localVideoTrack {
                VideoTrackView(track: track, mirrored: true)
                    .frame(width: 96, height: 144)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.15), lineWidth: 1)
                    )
                    .shadow(color: .black.opacity(0.4), radius: 10, y: 5)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        .padding(.trailing, 16)
        .padding(.bottom, 168)
        .allowsHitTesting(false)
    }
}
