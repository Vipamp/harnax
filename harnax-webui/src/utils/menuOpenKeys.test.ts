import { nextOpenKeys } from './menuOpenKeys';

describe('the accordion rule applied to a menu open-keys change', () => {
  it('opens the group the user just expanded', () => {
    expect(nextOpenKeys([], ['/system'])).toEqual(['/system']);
  });

  it('closes everything when the user collapsed the open group', () => {
    expect(nextOpenKeys(['/system'], [])).toEqual([]);
  });

  it('switches to the newly opened group and closes the previous one', () => {
    expect(nextOpenKeys(['/system'], ['/system', '/context'])).toEqual(['/context']);
  });

  it('opens only the parent on a deep link', () => {
    expect(nextOpenKeys([], ['/system', '/system/user'])).toEqual(['/system']);
  });

  it('keeps the parent open when navigating inside it', () => {
    expect(nextOpenKeys(['/system'], ['/system', '/system/user'])).toEqual(['/system']);
  });

  it('closes the open group when navigating to a top-level page', () => {
    expect(nextOpenKeys(['/system'], ['/welcome'])).toEqual(['/welcome']);
  });

  it('switches groups when navigation crosses groups', () => {
    expect(nextOpenKeys(['/system'], ['/context', '/context/model'])).toEqual(['/context']);
  });
});
