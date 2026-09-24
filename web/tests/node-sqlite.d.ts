declare module "node:sqlite" {
  export class DatabaseSync {
    constructor(path: string);
    exec(sql: string): void;
    prepare(sql: string): {
      run(...values: Array<string | number>): void;
      all(): Array<{ participant_id: string; rank: number }>;
    };
    close(): void;
  }
}
