import ComposeApp
import AVFoundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import WebKit

struct PlayerView: View {
    private enum PendingSubtitleAction {
        case search
        case importFile
        case paste
    }

    let candidate: ComposeApp.MediaCandidate

    @Environment(\.dismiss) private var dismiss
    @AppStorage("SniffSubPlayer.playerAccentColor") private var playerAccentColorID = PlayerAccentColor.defaultValue
    @State private var player = AVPlayer()
    @State private var timeObserver: Any?
    @State private var cues: [SubtitleCue] = []
    @State private var subtitleTimeline = SubtitleTimeline()
    @State private var subtitleText = ""
    @State private var subtitleOffset = 0.0
    @State private var subtitleSize = 30.0
    @State private var subtitleBottom = 38.0
    @State private var showingSubtitleImporter = false
    @State private var showingPasteSheet = false
    @State private var showingSubtitleSearch = false
    @State private var showingSubtitleSettings = false
    @State private var pendingSubtitleAction: PendingSubtitleAction?
    @State private var subtitlecatPage: WebSheetPage?
    @State private var pastedSubtitle = ""
    @State private var status = ""
    @State private var isPlaying = false
    @State private var currentTime = 0.0
    @State private var duration = 0.0
    @State private var isScrubbing = false
    @State private var searchQuery = ""
    @State private var onlineSubtitles: [OnlineSubtitle] = []
    @State private var isSearchingSubtitles = false
    @State private var showControls = true
    @State private var hideControlsTask: Task<Void, Never>?
    @State private var seekFeedbackText = ""
    @State private var seekFeedbackIcon = "goforward.10"
    @State private var showSeekFeedback = false
    @State private var seekFeedbackTask: Task<Void, Never>?
    @State private var panStartTime = 0.0
    @State private var brightnessStart = UIScreen.main.brightness
    @State private var volumeStart: Float = 1

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.ignoresSafeArea()

            PlayerLayerView(
                player: player,
                onSingleTap: toggleControlsVisibility,
                onDoubleTap: handleDoubleTap,
                onPan: handlePan,
                onVerticalPan: handleVerticalPan
            )
                .ignoresSafeArea()

            if showSeekFeedback {
                Label(seekFeedbackText, systemImage: seekFeedbackIcon)
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
                    .background(.black.opacity(0.68), in: Capsule())
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                    .offset(y: 86)
                    .transition(.scale(scale: 0.9).combined(with: .opacity))
                    .allowsHitTesting(false)
            }

            if !subtitleText.isEmpty {
                Text(subtitleText)
                    .font(.system(size: subtitleSize, weight: .bold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .lineLimit(4)
                    .shadow(color: .black, radius: 4, x: 0, y: 2)
                    .shadow(color: .black, radius: 10, x: 0, y: 0)
                    .padding(.horizontal, 18)
                    .padding(.bottom, subtitleBottom)
                    .allowsHitTesting(false)
            }

            if showControls {
                controls
                    .transition(.opacity)
            }

            if showingSubtitleSettings {
                subtitleSettingsOverlay
                    .transition(.scale(scale: 0.96).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.22), value: showControls)
        .animation(.easeInOut(duration: 0.2), value: showingSubtitleSettings)
        .fileImporter(
            isPresented: $showingSubtitleImporter,
            allowedContentTypes: subtitleContentTypes,
            allowsMultipleSelection: false,
            onCompletion: importSubtitle
        )
        .sheet(isPresented: $showingPasteSheet) {
            pasteSubtitleSheet
        }
        .sheet(isPresented: $showingSubtitleSearch) {
            subtitleSearchSheet
        }
        .sheet(item: $subtitlecatPage) { page in
            NavigationStack {
                SubtitlecatWebView(url: page.url)
                    .navigationTitle("Subtitlecat")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("关闭") { subtitlecatPage = nil }
                        }
                    }
            }
        }
        .onAppear {
            searchQuery = VideoIDExtractor.extract(from: [candidate.pageUrl, candidate.url])
            OrientationManager.lock([.landscapeLeft, .landscapeRight], preferred: .landscapeRight)
            configureAudioSession()
            configurePlayer()
            addTimeObserver()
            scheduleControlsAutoHide()
        }
        .onDisappear {
            hideControlsTask?.cancel()
            seekFeedbackTask?.cancel()
            player.pause()
            if let timeObserver {
                player.removeTimeObserver(timeObserver)
            }
            OrientationManager.unlock()
        }
    }

    private var playerAccentColor: Color {
        PlayerAccentColor.color(from: playerAccentColorID)
    }

    private var controls: some View {
        ZStack {
            LinearGradient(
                colors: [.black.opacity(0.62), .clear, .black.opacity(0.76)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .contentShape(Rectangle())
            .onTapGesture {
                toggleControlsVisibility()
            }

            VStack(spacing: 0) {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 38, height: 38)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .accessibilityLabel("关闭播放器")

                    if !status.isEmpty {
                        Text(status)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.76))
                            .lineLimit(1)
                            .padding(.leading, 8)
                    }

                    Spacer()

                    Button {
                        keepControlsVisibleForInteraction()
                        withAnimation {
                            showingSubtitleSettings = true
                        }
                    } label: {
                        Image(systemName: cues.isEmpty ? "captions.bubble" : "captions.bubble.fill")
                            .font(.system(size: 17, weight: .semibold))
                            .frame(width: 44, height: 38)
                            .background(.ultraThinMaterial, in: Capsule())
                    }
                    .accessibilityLabel("字幕设置")
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 22)
                .padding(.top, 14)

                Spacer()

                HStack(spacing: 10) {
                    Text(formatTime(currentTime))
                        .frame(width: 54, alignment: .leading)

                    PlayerProgressBar(
                        value: Binding(
                            get: { currentTime },
                            set: {
                                isScrubbing = true
                                currentTime = $0
                                keepControlsVisibleForInteraction()
                            }
                        ),
                        range: 0...max(duration, 1),
                        accentColor: playerAccentColor,
                        onEditingChanged: { editing in
                            if !editing {
                                let target = CMTime(seconds: currentTime, preferredTimescale: 600)
                                let tolerance = CMTime(seconds: 0.25, preferredTimescale: 600)
                                player.seek(to: target, toleranceBefore: tolerance, toleranceAfter: tolerance) { _ in
                                    Task { @MainActor in
                                        isScrubbing = false
                                    }
                                }
                                scheduleControlsAutoHide()
                            }
                        }
                    )

                    Text(formatTime(duration))
                        .frame(width: 54, alignment: .trailing)
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.9))
                .padding(.horizontal, 24)
                .padding(.bottom, 18)
            }

            HStack(spacing: 42) {
                playerControlButton(systemName: "gobackward.10", size: 26) {
                    keepControlsVisibleForInteraction()
                    seekBy(-10)
                }

                playerControlButton(systemName: isPlaying ? "pause.fill" : "play.fill", size: 31, prominent: true) {
                    keepControlsVisibleForInteraction()
                    togglePlay()
                }

                playerControlButton(systemName: "goforward.10", size: 26) {
                    keepControlsVisibleForInteraction()
                    seekBy(10)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func playerControlButton(
        systemName: String,
        size: CGFloat,
        prominent: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: size, weight: .semibold))
                .frame(width: prominent ? 70 : 54, height: prominent ? 70 : 54)
                .background(.ultraThinMaterial, in: Circle())
        }
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.32), radius: 12, y: 5)
    }

    private var subtitleSettingsOverlay: some View {
        ZStack {
            Color.black.opacity(0.3)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation { showingSubtitleSettings = false }
                }

            VStack(spacing: 16) {
                HStack {
                    Text("字幕设置")
                        .font(.headline)
                    Spacer()
                    Button {
                        withAnimation { showingSubtitleSettings = false }
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                }

                HStack(spacing: 10) {
                    subtitleActionButton("在线搜索", systemImage: "magnifyingglass", action: .search)
                    subtitleActionButton("字幕文件", systemImage: "doc.badge.plus", action: .importFile)
                    subtitleActionButton("查看字幕", systemImage: "doc.on.clipboard", action: .paste)
                }

                Divider()

                HStack(spacing: 12) {
                    Text("同步")
                        .frame(width: 52, alignment: .leading)
                    Button("−0.5") { adjustOffset(-0.5) }
                        .buttonStyle(.bordered)
                    Text(String(format: "%+.1f 秒", subtitleOffset))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 70)
                    Button("+0.5") { adjustOffset(0.5) }
                        .buttonStyle(.bordered)
                    Spacer()
                }

                VStack(spacing: 12) {
                    HStack {
                        Text("字号")
                            .frame(width: 52, alignment: .leading)
                        Slider(value: $subtitleSize, in: 18...56, step: 1)
                            .tint(playerAccentColor)
                        Text("\(Int(subtitleSize)) pt")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(width: 52, alignment: .trailing)
                    }

                    HStack {
                        Text("位置")
                            .frame(width: 52, alignment: .leading)
                        Slider(value: $subtitleBottom, in: 8...180, step: 1)
                            .tint(playerAccentColor)
                        Text("\(Int(subtitleBottom)) pt")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                            .frame(width: 52, alignment: .trailing)
                    }
                }
            }
            .padding(20)
            .frame(maxWidth: 520)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(.white.opacity(0.12), lineWidth: 1)
            }
            .shadow(color: .black.opacity(0.38), radius: 30, y: 14)
            .padding(.horizontal, 28)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func subtitleActionButton(
        _ title: String,
        systemImage: String,
        action: PendingSubtitleAction
    ) -> some View {
        Button {
            requestSubtitleAction(action)
        } label: {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
        }
        .buttonStyle(.bordered)
        .tint(.primary)
    }

    private var pasteSubtitleSheet: some View {
        NavigationStack {
            TextEditor(text: $pastedSubtitle)
                .font(.system(.body, design: .monospaced))
                .padding()
                .navigationTitle("粘贴 SRT / VTT")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showingPasteSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("应用") {
                            applySubtitle(pastedSubtitle, label: "文本")
                            showingPasteSheet = false
                        }
                    }
                }
        }
    }

    private var subtitleSearchSheet: some View {
        NavigationStack {
            VStack(spacing: 12) {
                HStack {
                    TextField("关键词", text: $searchQuery)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)

                    Button("字幕搜索") {
                        Task { await searchOnlineSubtitles() }
                    }
                    .disabled(isSearchingSubtitles)
                }

                if !status.isEmpty {
                    Text(status)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                HStack {
                    Button("打开 Subtitlecat") {
                        openSubtitlecat()
                    }

                    if isSearchingSubtitles {
                        ProgressView()
                    }

                    Spacer()
                }

                if onlineSubtitles.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "captions.bubble")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("暂无字幕结果")
                            .font(.headline)
                        Text("可先在线搜索，或打开 Subtitlecat 自行下载后导入字幕文件。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(onlineSubtitles) { item in
                        Button {
                            Task { await loadRemoteSubtitle(item) }
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.displayName)
                                    .font(.body)
                                Text(item.url)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                        }
                    }
                }
            }
            .padding()
            .navigationTitle("在线字幕")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { showingSubtitleSearch = false }
                }
            }
        }
    }

    private func requestSubtitleAction(_ action: PendingSubtitleAction) {
        pendingSubtitleAction = action
        withAnimation { showingSubtitleSettings = false }

        Task {
            try? await Task.sleep(nanoseconds: 220_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                performPendingSubtitleAction()
            }
        }
    }

    private func performPendingSubtitleAction() {
        guard let action = pendingSubtitleAction else { return }
        pendingSubtitleAction = nil

        switch action {
        case .search:
            showingSubtitleSearch = true
        case .importFile:
            showingSubtitleImporter = true
        case .paste:
            showingPasteSheet = true
        }
    }

    private var subtitleContentTypes: [UTType] {
        [
            .plainText,
            UTType(filenameExtension: "srt"),
            UTType(filenameExtension: "vtt")
        ].compactMap { $0 }
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            status = "音频会话设置失败: \(error.localizedDescription)"
        }
    }

    private func configurePlayer() {
        guard let url = URL(string: candidate.url), candidate.kind != .blob else {
            status = "Blob 链接不能离开原网页直接播放，请选择 m3u8/mp4 候选"
            return
        }

        let headers = [
            "User-Agent": candidate.userAgent,
            "Referer": candidate.referer
        ].filter { !$0.value.isEmpty }

        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        player.volume = 1
        player.isMuted = false
        player.play()
        isPlaying = true
    }

    private func addTimeObserver() {
        let interval = CMTime(seconds: 0.2, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { time in
            if !isScrubbing {
                currentTime = time.seconds.isFinite ? time.seconds : 0
            }
            duration = player.currentItem?.duration.seconds ?? 0
            if !duration.isFinite { duration = 0 }
            let wasPlaying = isPlaying
            isPlaying = player.timeControlStatus == .playing
            if isPlaying && !wasPlaying {
                scheduleControlsAutoHide()
            }
            updateSubtitle(at: time.seconds)
        }
    }

    private func togglePlay() {
        if player.timeControlStatus == .playing {
            player.pause()
            isPlaying = false
            showControls = true
            hideControlsTask?.cancel()
        } else {
            player.play()
            isPlaying = true
            scheduleControlsAutoHide()
        }
    }

    private func seekBy(_ delta: TimeInterval) {
        let next = max(0, min(duration > 0 ? duration : .greatestFiniteMagnitude, currentTime + delta))
        currentTime = next
        player.seek(to: CMTime(seconds: next, preferredTimescale: 600))
    }

    private func handleDoubleTap(_ direction: PlayerSeekDirection) {
        let delta: TimeInterval = direction == .forward ? 10 : -10
        seekBy(delta)
        UIImpactFeedbackGenerator(style: .light).impactOccurred()

        seekFeedbackTask?.cancel()
        seekFeedbackText = direction == .forward ? "快进 10 秒" : "后退 10 秒"
        seekFeedbackIcon = direction == .forward ? "goforward.10" : "gobackward.10"
        withAnimation(.easeOut(duration: 0.16)) {
            showSeekFeedback = true
        }

        seekFeedbackTask = Task {
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeIn(duration: 0.16)) {
                    showSeekFeedback = false
                }
            }
        }
    }

    private func handlePan(_ event: PlayerPanEvent) {
        guard duration.isFinite, duration > 0 else { return }

        switch event {
        case .began:
            seekFeedbackTask?.cancel()
            panStartTime = currentTime
            isScrubbing = true
            seekFeedbackIcon = "arrow.left.and.right"
            showSeekFeedback = true

        case .changed(let progress):
            let seekRange = min(max(duration * 0.1, 60), 600)
            currentTime = min(duration, max(0, panStartTime + Double(progress) * seekRange))
            seekFeedbackText = "\(formatTime(currentTime)) / \(formatTime(duration))"

        case .ended:
            let target = CMTime(seconds: currentTime, preferredTimescale: 600)
            let tolerance = CMTime(seconds: 0.25, preferredTimescale: 600)
            player.seek(to: target, toleranceBefore: tolerance, toleranceAfter: tolerance) { _ in
                Task { @MainActor in
                    isScrubbing = false
                }
            }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            hideSeekFeedbackAfterDelay()

        case .cancelled:
            currentTime = panStartTime
            isScrubbing = false
            hideSeekFeedbackAfterDelay()
        }
    }

    private func hideSeekFeedbackAfterDelay() {
        seekFeedbackTask?.cancel()
        seekFeedbackTask = Task {
            try? await Task.sleep(nanoseconds: 450_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeIn(duration: 0.16)) {
                    showSeekFeedback = false
                }
            }
        }
    }

    private func handleVerticalPan(_ event: PlayerVerticalPanEvent) {
        switch event {
        case .began(let control):
            seekFeedbackTask?.cancel()
            brightnessStart = UIScreen.main.brightness
            volumeStart = player.volume
            seekFeedbackIcon = control == .brightness ? "sun.max.fill" : "speaker.wave.2.fill"
            showSeekFeedback = true

        case .changed(let control, let progress):
            let value: Double
            switch control {
            case .brightness:
                value = min(1, max(0, brightnessStart + Double(progress)))
                UIScreen.main.brightness = value
            case .volume:
                value = min(1, max(0, Double(volumeStart) + Double(progress)))
                player.volume = Float(value)
            }
            seekFeedbackText = "\(Int((value * 100).rounded()))%"

        case .ended, .cancelled:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            hideSeekFeedbackAfterDelay()
        }
    }

    private func updateSubtitle(at seconds: TimeInterval) {
        subtitleText = subtitleTimeline.textAt(positionMs: SubtitleParser.milliseconds(seconds))
    }

    private func importSubtitle(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }

            let data = try Data(contentsOf: url)
            let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .unicode)
                ?? String(data: data, encoding: .shiftJIS)
                ?? ""
            applySubtitle(text, label: url.lastPathComponent)
        } catch {
            status = "字幕导入失败: \(error.localizedDescription)"
        }
    }

    private func loadSharedSubtitles(_ text: String) -> [SubtitleCue] {
        subtitleTimeline.load(text: text, offsetMs: SubtitleParser.milliseconds(subtitleOffset)).map {
            SubtitleCue(start: Double($0.startMs) / 1000, end: Double($0.endMs) / 1000, text: $0.text)
        }
    }

    private func applySubtitle(_ text: String, label: String) {
        cues = loadSharedSubtitles(text)
        pastedSubtitle = text
        status = cues.isEmpty ? "没有解析到有效字幕" : "已载入 \(cues.count) 条字幕 (\(label))"
    }

    private func adjustOffset(_ delta: TimeInterval) {
        subtitleOffset += delta
        if !pastedSubtitle.isEmpty {
            cues = loadSharedSubtitles(pastedSubtitle)
        }
        status = String(format: "字幕偏移 %.1fs", subtitleOffset)
    }

    private func adjustSubtitleBottom(_ delta: Double) {
        subtitleBottom = clampedSubtitleBottom(subtitleBottom + delta)
        status = "字幕底距 \(Int(subtitleBottom))pt"
    }

    private func clampedSubtitleBottom(_ value: Double) -> Double {
        min(180, max(8, value))
    }

    private func toggleControlsVisibility() {
        showControls.toggle()
        if showControls {
            scheduleControlsAutoHide()
        } else {
            hideControlsTask?.cancel()
        }
    }

    private func keepControlsVisibleForInteraction() {
        showControls = true
        scheduleControlsAutoHide()
    }

    private func scheduleControlsAutoHide() {
        hideControlsTask?.cancel()

        guard player.timeControlStatus == .playing || isPlaying else {
            showControls = true
            return
        }

        hideControlsTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard !showingPasteSheet,
                      !showingSubtitleSearch,
                      !showingSubtitleImporter,
                      subtitlecatPage == nil,
                      player.timeControlStatus == .playing else {
                    return
                }
                showControls = false
            }
        }
    }

    private func searchOnlineSubtitles() async {
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            status = "请输入番号或关键词后再搜索"
            return
        }

        isSearchingSubtitles = true
        status = "正在搜索字幕: \(query)"
        defer { isSearchingSubtitles = false }

        do {
            var components = URLComponents(string: "https://api-shoulei-ssl.xunlei.com/oracle/subtitle")
            components?.queryItems = [URLQueryItem(name: "name", value: query)]
            guard let url = components?.url else { throw URLError(.badURL) }

            var request = URLRequest(url: url)
            request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw URLError(.badServerResponse)
            }

            let decoded = try JSONDecoder().decode(RemoteSubtitleSearchResponse.self, from: data)
            onlineSubtitles = (decoded.data ?? [])
                .filter { $0.url.lowercased().contains(".srt") || $0.url.lowercased().contains(".vtt") }
            status = onlineSubtitles.isEmpty ? "未找到在线字幕" : "找到 \(onlineSubtitles.count) 个字幕"
        } catch {
            status = "在线字幕搜索失败: \(error.localizedDescription)"
        }
    }

    private func loadRemoteSubtitle(_ item: OnlineSubtitle) async {
        guard let url = URL(string: item.url) else {
            status = "字幕链接无效"
            return
        }

        do {
            var request = URLRequest(url: url)
            request.setValue("text/plain,*/*", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw URLError(.badServerResponse)
            }

            let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .unicode)
                ?? String(data: data, encoding: .shiftJIS)
                ?? ""
            applySubtitle(text, label: item.name)
            showingSubtitleSearch = false
        } catch {
            status = "字幕下载失败: \(error.localizedDescription)"
        }
    }

    private func openSubtitlecat() {
        let query = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        if let url = URL(string: "https://subtitlecat.com/index.php?search=\(encoded)") {
            subtitlecatPage = WebSheetPage(url: url)
        }
    }

    private func formatTime(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "00:00" }
        let total = Int(seconds.rounded(.down))
        let s = total % 60
        let m = (total / 60) % 60
        let h = total / 3600
        if h > 0 {
            return String(format: "%02d:%02d:%02d", h, m, s)
        }
        return String(format: "%02d:%02d", m, s)
    }
}

struct PlayerProgressBar: View {
    @Binding var value: Double
    let range: ClosedRange<Double>
    let accentColor: Color
    let onEditingChanged: (Bool) -> Void

    @State private var isEditing = false

    var body: some View {
        GeometryReader { geometry in
            let width = max(geometry.size.width, 1)
            let fraction = progressFraction

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(.white.opacity(0.28))
                    .frame(height: 4)

                Capsule()
                    .fill(accentColor)
                    .frame(width: width * fraction, height: 4)

                Circle()
                    .fill(.white)
                    .frame(width: 14, height: 14)
                    .shadow(color: .black.opacity(0.28), radius: 3, y: 1)
                    .offset(x: max(0, min(width - 14, width * fraction - 7)))
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .local)
                    .onChanged { gesture in
                        if !isEditing {
                            isEditing = true
                            onEditingChanged(true)
                        }
                        updateValue(at: gesture.location.x, width: width)
                    }
                    .onEnded { gesture in
                        updateValue(at: gesture.location.x, width: width)
                        isEditing = false
                        onEditingChanged(false)
                    }
            )
        }
        .frame(height: 30)
        .accessibilityElement()
        .accessibilityLabel("播放进度")
        .accessibilityValue("\(Int(progressFraction * 100))%")
        .accessibilityAdjustableAction { direction in
            let step = (range.upperBound - range.lowerBound) * 0.05
            switch direction {
            case .increment:
                value = min(range.upperBound, value + step)
            case .decrement:
                value = max(range.lowerBound, value - step)
            @unknown default:
                break
            }
            onEditingChanged(false)
        }
    }

    private var progressFraction: CGFloat {
        let span = range.upperBound - range.lowerBound
        guard span > 0 else { return 0 }
        return CGFloat(min(1, max(0, (value - range.lowerBound) / span)))
    }

    private func updateValue(at x: CGFloat, width: CGFloat) {
        let fraction = Double(min(1, max(0, x / width)))
        value = range.lowerBound + fraction * (range.upperBound - range.lowerBound)
    }
}

enum PlayerSeekDirection {
    case backward
    case forward
}

enum PlayerPanEvent {
    case began
    case changed(CGFloat)
    case ended
    case cancelled
}

enum PlayerVerticalControl {
    case brightness
    case volume
}

enum PlayerVerticalPanEvent {
    case began(PlayerVerticalControl)
    case changed(PlayerVerticalControl, CGFloat)
    case ended
    case cancelled
}

struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    let onSingleTap: () -> Void
    let onDoubleTap: (PlayerSeekDirection) -> Void
    let onPan: (PlayerPanEvent) -> Void
    let onVerticalPan: (PlayerVerticalPanEvent) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            onSingleTap: onSingleTap,
            onDoubleTap: onDoubleTap,
            onPan: onPan,
            onVerticalPan: onVerticalPan
        )
    }

    func makeUIView(context: Context) -> PlayerLayerContainerView {
        let view = PlayerLayerContainerView()
        view.playerLayer.player = player

        let singleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleSingleTap))
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        singleTap.require(toFail: doubleTap)
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePan(_:)))
        pan.name = "horizontalSeek"
        pan.delegate = context.coordinator
        let verticalPan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleVerticalPan(_:)))
        verticalPan.name = "verticalControl"
        verticalPan.delegate = context.coordinator
        view.addGestureRecognizer(singleTap)
        view.addGestureRecognizer(doubleTap)
        view.addGestureRecognizer(pan)
        view.addGestureRecognizer(verticalPan)
        return view
    }

    func updateUIView(_ uiView: PlayerLayerContainerView, context: Context) {
        uiView.playerLayer.player = player
        context.coordinator.onSingleTap = onSingleTap
        context.coordinator.onDoubleTap = onDoubleTap
        context.coordinator.onPan = onPan
        context.coordinator.onVerticalPan = onVerticalPan
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onSingleTap: () -> Void
        var onDoubleTap: (PlayerSeekDirection) -> Void
        var onPan: (PlayerPanEvent) -> Void
        var onVerticalPan: (PlayerVerticalPanEvent) -> Void
        private var activeVerticalControl: PlayerVerticalControl = .brightness

        init(
            onSingleTap: @escaping () -> Void,
            onDoubleTap: @escaping (PlayerSeekDirection) -> Void,
            onPan: @escaping (PlayerPanEvent) -> Void,
            onVerticalPan: @escaping (PlayerVerticalPanEvent) -> Void
        ) {
            self.onSingleTap = onSingleTap
            self.onDoubleTap = onDoubleTap
            self.onPan = onPan
            self.onVerticalPan = onVerticalPan
        }

        @objc func handleSingleTap() {
            onSingleTap()
        }

        @objc func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let direction: PlayerSeekDirection = recognizer.location(in: view).x < view.bounds.midX ? .backward : .forward
            onDoubleTap(direction)
        }

        @objc func handlePan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view, view.bounds.width > 0 else { return }
            let translation = recognizer.translation(in: view)

            switch recognizer.state {
            case .began:
                onPan(.began)
            case .changed:
                onPan(.changed(translation.x / view.bounds.width))
            case .ended:
                onPan(.ended)
            case .cancelled, .failed:
                onPan(.cancelled)
            default:
                break
            }
        }

        @objc func handleVerticalPan(_ recognizer: UIPanGestureRecognizer) {
            guard let view = recognizer.view, view.bounds.height > 0 else { return }
            let translation = recognizer.translation(in: view)

            switch recognizer.state {
            case .began:
                activeVerticalControl = recognizer.location(in: view).x < view.bounds.midX ? .brightness : .volume
                onVerticalPan(.began(activeVerticalControl))
            case .changed:
                onVerticalPan(.changed(activeVerticalControl, -translation.y / view.bounds.height))
            case .ended:
                onVerticalPan(.ended)
            case .cancelled, .failed:
                onVerticalPan(.cancelled)
            default:
                break
            }
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return true }
            let velocity = pan.velocity(in: pan.view)
            if pan.name == "verticalControl" {
                return abs(velocity.y) > abs(velocity.x) * 1.2
            }
            return abs(velocity.x) > abs(velocity.y) * 1.2
        }
    }
}

final class PlayerLayerContainerView: UIView {
    override class var layerClass: AnyClass {
        AVPlayerLayer.self
    }

    var playerLayer: AVPlayerLayer {
        layer as! AVPlayerLayer
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        playerLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

struct SubtitlecatWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        if webView.url != url {
            webView.load(URLRequest(url: url))
        }
    }
}

struct WebSheetPage: Identifiable {
    let id = UUID()
    let url: URL
}
