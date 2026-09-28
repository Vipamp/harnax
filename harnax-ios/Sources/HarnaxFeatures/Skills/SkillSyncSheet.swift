import SwiftUI
import HarnaxCore
import HarnaxKit

/// The two-step sync modal. It is a sheet rather than a menu action because the operator has to see what
/// the source holds *before* anything is written, and then read what the write did
/// (`harnax-webui/src/pages/skill/index.tsx:127-152`).
///
/// The state machine lives in `SkillSyncModel`; this view only draws its phases.
public struct SkillSyncSheet: View {
    @StateObject private var model: SkillSyncModel
    @Environment(\.dismiss) private var dismiss
    private let onChanged: () -> Void

    public init(source: SkillSourceSummary, skills: any SkillCataloging, onChanged: @escaping () -> Void = {}) {
        _model = StateObject(wrappedValue: SkillSyncModel(source: source, skills: skills))
        self.onChanged = onChanged
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        HXText("skill.sync.hint")
                            .font(.footnote)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .fixedSize(horizontal: false, vertical: true)
                        if let error = model.submitError {
                            HXBanner("state.error.title", message: error, systemImage: "exclamationmark.triangle", tone: .danger)
                        }
                        content
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                footer
            }
            .background(Color.hx(.background))
            .navigationTitle(Text(verbatim: hx("skill.sync.title")))
#if canImport(UIKit)
            .navigationBarTitleDisplayMode(.inline)
#endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { HXText("common.cancel") }
                }
            }
            .task { await model.load() }
            .onChange(of: model.isFinished) {
                if model.isFinished { dismiss() }
            }
            .onChange(of: model.didChangeSkills) {
                // The right-hand table reloads whatever the install answered, clean or not
                // (`SyncSkillModal.tsx:66-89`).
                if model.didChangeSkills { onChanged() }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            HXStateView(.loading)
        case let .failed(message):
            // A failed read is not an empty source: the two say opposite things about whether the remote
            // is reachable at all.
            HXStateView(.error, message: message, retry: { Task { await model.load() } })
        case .empty:
            HXStateView(.empty, message: hx("skill.sync.empty"))
        case .ready:
            if let report = model.report {
                SkillInstallReportCard(report: report)
            } else {
                checklist
            }
        }
    }

    private var checklist: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button { model.selectAll() } label: { HXText("skill.sync.all") }
                    .buttonStyle(.hxInline)
                Button { model.selectNone() } label: { HXText("skill.sync.none") }
                    .buttonStyle(.hxInline)
                Spacer(minLength: 0)
            }
            ForEach(model.rows) { item in
                row(item)
            }
        }
        .background(Color.hx(.surface), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func row(_ item: SkillPreviewItem) -> some View {
        Button { model.toggle(item) } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: model.selected.contains(item.id) ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(Color.hx(model.selected.contains(item.id) ? .brand : .textTertiary))
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(verbatim: item.title ?? item.id)
                            .font(.subheadline)
                            .foregroundStyle(Color.hx(.textPrimary))
                            .lineLimit(1)
                        HXChip(hx(item.exists ? "skill.sync.exists" : "skill.sync.new"), tone: item.exists ? .indigo : .success)
                    }
                    if let detail = item.detail {
                        Text(verbatim: detail)
                            .font(.caption)
                            .foregroundStyle(Color.hx(.textSecondary))
                            .lineLimit(2)
                    }
                    if item.resourceCount > 0 {
                        HXChip(hx("skill.sync.files", item.resourceCount), tone: .teal)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.report != nil)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            if model.report == nil, model.selectedCount > 0 {
                Text(verbatim: hx("skill.sync.new.count", model.isNewCount))
                    .font(.footnote)
                    .foregroundStyle(Color.hx(.textSecondary))
            }
            if let report = model.report {
                Button { model.closeAfterReport() } label: {
                    HXText(report.tone == .success ? "common.confirm" : "common.close")
                }
                .buttonStyle(.hxPrimary)
            } else {
                Button {
                    Task { await model.submit() }
                } label: {
                    Text(verbatim: model.primaryTitle)
                }
                .buttonStyle(.hxPrimary)
                .disabled(model.selectedCount == 0 || model.isSubmitting || model.phase == .loading)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }
}
