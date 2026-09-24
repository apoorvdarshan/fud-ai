declare module "node:sqlite" {
  export class DatabaseSync {
    constructor(path: string);
    exec(sql: string): void;
    prepare(sql: string): {
      run(...values: Array<string | number>): void;
      all(...values: Array<string | number>): Array<Record<string, string | number>>;
    };
    close(): void;
  }
}
