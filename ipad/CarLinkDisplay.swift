import SwiftUI
import UIKit

@main
struct CarLinkDisplay: App {
    var body: some Scene {
        WindowGroup { RelayView() }
    }
}

private struct RelayView: View {
    @State private var host = ""
    @State private var code = ""
    @State private var endpoint: URL?
    @State private var frame: UIImage?
    @State private var status = ""
    @State private var showControls = true

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let frame {
                Image(uiImage: frame)
                    .resizable()
                    .scaledToFit()
            }
            if showControls {
                VStack(spacing: 16) {
                    Text("CarLink Display").font(.title)
                    if endpoint == nil {
                        TextField("S22 hotspot router IP", text: $host)
                            .keyboardType(.numbersAndPunctuation)
                            .autocorrectionDisabled()
                        SecureField("Relay code", text: $code)
                            .keyboardType(.numberPad)
                        Button("Connect", action: connect)
                    } else {
                        Button("Disconnect") {
                            endpoint = nil
                            frame = nil
                            status = ""
                        }
                    }
                    if !status.isEmpty { Text(status).font(.footnote) }
                }
                .textFieldStyle(.roundedBorder)
                .padding(24)
                .frame(maxWidth: 380)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if frame != nil { showControls.toggle() }
        }
        .task(id: endpoint) {
            if let endpoint { await receive(from: endpoint) }
        }
    }

    private func connect() {
        let address = host.trimmingCharacters(in: .whitespacesAndNewlines)
        let octets = address.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4,
              octets.allSatisfy({ UInt8($0) != nil }),
              code.utf8.count == 9, code.utf8.allSatisfy({ (48...57).contains($0) }),
              let url = URL(string: "http://\(address):8765/frame/\(code)") else {
            status = "Enter the hotspot's IPv4 router address and the nine-digit code."
            return
        }
        status = "Connecting…"
        endpoint = url
    }

    @MainActor
    private func receive(from url: URL) async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 2
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        while !Task.isCancelled {
            do {
                let (bytes, response) = try await session.data(from: url)
                guard !Task.isCancelled else { return }
                switch (response as? HTTPURLResponse)?.statusCode {
                case 200:
                    if let image = UIImage(data: bytes) {
                        if frame == nil { showControls = false }
                        frame = image
                        status = ""
                    }
                case 204:
                    status = "Waiting for screen capture…"
                case 404:
                    endpoint = nil
                    status = "Check the relay code on the S22."
                    return
                default:
                    status = "Unexpected response from the S22."
                    showControls = true
                }
            } catch {
                guard !Task.isCancelled else { return }
                frame = nil
                showControls = true
                status = "Relay unavailable. Check the hotspot and screen sharing."
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
    }
}
