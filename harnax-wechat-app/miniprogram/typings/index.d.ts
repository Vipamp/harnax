/// <reference path="./api.d.ts" />

interface IUserInfo {
  id?: number;
  username?: string;
  nickname?: string;
  avatar?: string;
  roles?: string[];
}

interface IAppOption {
  globalData: {
    version: string;
    userInfo?: IUserInfo;
  };
  onLaunch?: () => void;
}
