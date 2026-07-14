/// <reference types="vite/client" />

declare module "dayjs" {
  interface Dayjs {
    fromNow(withoutSuffix?: boolean): string;
  }
}

declare module "*.less" {
  const classes: { [key: string]: string };
  export default classes;
}

export {};
