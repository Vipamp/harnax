import { getMatchMenu, transformRoute } from '@umijs/route-utils';

import routes from '../../../config/routes';

type MenuNode = {
  key?: string;
  path?: string;
  name?: string;
  layout?: false;
  component?: string;
  children?: MenuNode[];
};

/** Same pipeline ProLayout runs: routes -> filtered menuData -> matched keys -> selectedKeys. */
const menuData = transformRoute(routes as never[], false, undefined, false)
  .menuData as unknown as MenuNode[];

const identity = (node: MenuNode) => node.key || node.path || '';

/** Submenu titles can't look focused, so only rows backed by a page count. */
const groupKeys = new Set<string>();
const collectGroups = (nodes: MenuNode[]) => {
  nodes.forEach((node) => {
    if (node.children && node.children.length > 0) {
      groupKeys.add(identity(node));
      collectGroups(node.children);
    }
  });
};
collectGroups(menuData);

const focusedRowsAt = (pathname: string) =>
  getMatchMenu(pathname, menuData as never, true)
    .map((item) => identity(item as MenuNode))
    .filter((key) => !groupKeys.has(key));

const pageRows: MenuNode[] = [];
const collectPages = (nodes: MenuNode[], insideLayout = true) => {
  nodes.forEach((node) => {
    // A route tree with layout: false renders no sidebar, so nothing can be focused there.
    const stillInLayout = insideLayout && node.layout !== false;
    if (node.component && stillInLayout) {
      pageRows.push(node);
    }
    if (node.children) {
      collectPages(node.children, stillInLayout);
    }
  });
};
collectPages(menuData);

describe('sidebar focus follows one row per location', () => {
  it('covers both memory rows so the guard is not vacuous', () => {
    const components = pageRows.map((row) => row.component);
    expect(components).toEqual(
      expect.arrayContaining(['./memory', './memory/drafts']),
    );
    expect(pageRows.length).toBeGreaterThan(10);
  });

  it.each(pageRows.map((row) => [row.path, row.name]))(
    'highlights exactly the row whose own address is %s',
    (pathname) => {
      expect(focusedRowsAt(pathname as string)).toEqual([pathname]);
    },
  );

  it('keeps the approval row focused while a draft is open', () => {
    expect(focusedRowsAt('/optimization/memory-drafts/detail/42')).toEqual([
      '/optimization/memory-drafts',
    ]);
  });
});
