import SwiftUI
import UIKit
import Network
import AVFoundation

private final class HotspotRoute: ObservableObject {
    @Published private(set) var router: String?
    private let monitor = NWPathMonitor(requiredInterfaceType: .wifi)

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let address = path.gateways.compactMap { endpoint -> String? in
                guard case let .hostPort(host, _) = endpoint,
                      case let .ipv4(ip) = host else { return nil }
                return "\(ip)"
            }.first
            DispatchQueue.main.async { self?.router = address }
        }
        monitor.start(queue: DispatchQueue(label: "CarLink.hotspot"))
    }

    deinit { monitor.cancel() }
}

@main
struct CarLinkDisplay: App {
    var body: some Scene {
        WindowGroup { RelayView() }
    }
}

private struct RelayView: View {
    @StateObject private var route = HotspotRoute()
    @State private var code = ""
    @State private var endpoint: URL?
    @State private var frame: UIImage?
    @State private var status = ""
    @State private var showControls = true

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let frame {
                GeometryReader { viewport in
                    let scale = min(viewport.size.width / frame.size.width, viewport.size.height / frame.size.height)
                    Image(uiImage: frame)
                    .resizable()
                    .frame(width: frame.size.width * scale, height: frame.size.height * scale)
                    .overlay {
                        GeometryReader { bounds in
                            Color.clear.contentShape(Rectangle())
                                .gesture(DragGesture(minimumDistance: 0).onEnded { drag in
                                    guard let endpoint, !showControls else { return }
                                    let size = bounds.size
                                    let points = [drag.startLocation.x / size.width, drag.startLocation.y / size.height,
                                                  drag.location.x / size.width, drag.location.y / size.height]
                                    let values = points.map { String(Double(min(1, max(0, $0)))) }.joined(separator: ",")
                                    let base = endpoint.deletingLastPathComponent().deletingLastPathComponent()
                                    var request = URLRequest(url: base.appendingPathComponent("touch")
                                        .appendingPathComponent(endpoint.lastPathComponent).appendingPathComponent(values))
                                    request.httpMethod = "POST"
                                    Task {
                                        do {
                                            let (_, response) = try await URLSession.shared.data(for: request)
                                            if (response as? HTTPURLResponse)?.statusCode != 204 {
                                                status = "The S22 rejected the touch."
                                                showControls = true
                                            }
                                        } catch {
                                            status = "Could not send touch to the S22."
                                            showControls = true
                                        }
                                    }
                                })
                        }
                    }
                    .position(x: viewport.size.width / 2, y: viewport.size.height / 2)
                }
                .ignoresSafeArea()
            }
            if showControls {
                VStack(spacing: 16) {
                    Text("CarLink Display").font(.title)
                    if endpoint == nil {
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
        .overlay(alignment: .topTrailing) {
            if frame != nil {
                Button { showControls.toggle() } label: {
                    Image(systemName: "gearshape.fill").padding(10)
                }.accessibilityLabel("Connection settings")
            }
        }
        .onChange(of: frame != nil, initial: true) { _, displaying in
            UIApplication.shared.isIdleTimerDisabled = displaying
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .task(id: endpoint) {
            if let endpoint { await receive(from: endpoint) }
        }
        .task(id: endpoint) {
            if let endpoint { await playAudio(from: endpoint) }
        }
    }

    private func connect() {
        guard let address = route.router else {
            status = "Join the S22 hotspot and try again."
            return
        }
        guard code.utf8.count == 4, code.utf8.allSatisfy({ (48...57).contains($0) }),
              let url = URL(string: "http://\(address):8765/frame/\(code)") else {
            status = "Enter the four-digit code shown on the S22."
            return
        }
        status = "Connecting…"
        endpoint = url
    }

    @MainActor
    private func playAudio(from endpoint: URL) async {
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let session = AVAudioSession.sharedInstance()
        guard let format = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1) else { return }
        let url = endpoint.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("audio").appendingPathComponent(endpoint.lastPathComponent)
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        defer {
            player.stop()
            engine.stop()
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
        }
        do {
            try session.setCategory(.playback)
            try session.setActive(true)
            try engine.start()
            var queued = 0
            while !Task.isCancelled {
                if queued >= 4 {
                    try await Task.sleep(for: .milliseconds(10))
                    continue
                }
                let (data, response) = try await URLSession.shared.data(from: url)
                guard let http = response as? HTTPURLResponse, http.statusCode != 404 else { return }
                let count = data.count / 2
                guard http.statusCode == 200, count > 0, count <= 65536,
                      let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: UInt32(count)),
                      let samples = buffer.floatChannelData?[0] else {
                    if queued == 0 && player.isPlaying { player.stop() }
                    try await Task.sleep(for: .milliseconds(10))
                    continue
                }
                buffer.frameLength = UInt32(count)
                data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
                    for index in 0..<count {
                        let pcm = bytes.loadUnaligned(fromByteOffset: index * 2, as: Int16.self)
                        samples[index] = Float(Int16(littleEndian: pcm)) / 32768
                    }
                }
                queued += 1
                player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in
                    Task { @MainActor in queued -= 1 }
                }
                if !player.isPlaying && queued >= 3 { player.play() }
            }
        } catch {
            if !Task.isCancelled { status = "Audio disconnected. Reconnect to retry." }
        }
    }

    @MainActor
    private func receive(from url: URL) async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 2
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let bounds = UIScreen.main.nativeBounds
        let width = Int(max(bounds.width, bounds.height))
        let height = Int(min(bounds.width, bounds.height))
        let dimensions = "\(width),\(height),\(Int(UIScreen.main.nativeScale * 160))"
        let base = url.deletingLastPathComponent().deletingLastPathComponent()
        var setup = URLRequest(url: base.appendingPathComponent("display")
            .appendingPathComponent(url.lastPathComponent).appendingPathComponent(dimensions))
        setup.httpMethod = "POST"
        do {
            let (_, response) = try await session.data(for: setup)
            guard (response as? HTTPURLResponse)?.statusCode == 202 else {
                status = (response as? HTTPURLResponse)?.statusCode == 503
                    ? "The S22 could not create the relay display. Restart the relay."
                    : "Check the relay code and update both apps."
                endpoint = nil
                return
            }
        } catch {
            if !Task.isCancelled { status = "Could not reach the S22 hotspot."; endpoint = nil }
            return
        }
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
                    frame = nil
                    showControls = true
                    status = "Relay connected. Plug your Android Auto phone into the S22."
                case 404:
                    frame = nil
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
                status = "Connection to the S22 relay lost. Reopen CarLink Relay and check the hotspot."
            }
            try? await Task.sleep(for: .milliseconds(35))
        }
    }
}
