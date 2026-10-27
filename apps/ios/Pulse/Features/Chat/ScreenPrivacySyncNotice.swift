import SwiftUI

struct ScreenPrivacySyncNotice: View {
    @ObservedObject var prefs: PulsePrefs
    let room: String
    var body: some View {
        if let error = prefs.screenPrivacyReadError {
            Text(error).font(.footnote).foregroundStyle(.secondary)
            Button("Reload saved security choices") { prefs.reloadScreenPrivacy() }
                .font(.footnote)
        }
        if let note = prefs.screenPrivacyNotes[room] {
            Text(note).font(.footnote).foregroundStyle(.secondary)
        }
        if prefs.screenPrivacyPending.contains(room) {
            Button("Retry screen-security sync") { prefs.retryScreenPrivacy(conversationId: room) }
                .font(.footnote)
        }
    }
}
