import HarnaxKit

/// The status group as the shared filter menu wants it.
///
/// Eight lists filter on `StatusFilter` and eight copies of the same row would drift the day one of them
/// renames an option, so the mapping lives here and the screens hand over their selection.
func statusFilterChoices(_ selected: StatusFilter, _ select: @escaping (StatusFilter) -> Void) -> [HXFilterChoice] {
    StatusFilter.allCases.map { option in
        HXFilterChoice(
            id: option.titleKey,
            titleKey: option.titleKey,
            isSelected: option == selected
        ) { select(option) }
    }
}
