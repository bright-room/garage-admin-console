import { test, expect } from "@playwright/test";

test.describe("Sidebar Navigation", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(
      page.getByRole("button", { name: "Dashboard" })
    ).toBeVisible({ timeout: 30_000 });
  });

  test("navigates to Cluster screen", async ({ page }) => {
    await page.getByRole("button", { name: "Cluster" }).click({ force: true });
    await expect(page.getByText("Cluster Nodes")).toBeVisible();
  });

  test("navigates to Layout screen", async ({ page }) => {
    await page.getByRole("button", { name: "Layout" }).click({ force: true });
    await expect(page.getByText("Cluster Layout")).toBeVisible();
  });

  test("navigates to Buckets screen", async ({ page }) => {
    await page.getByRole("button", { name: "Buckets" }).click({ force: true });
    await expect(page.getByText("Create Bucket")).toBeVisible();
  });

  test("navigates to Keys screen", async ({ page }) => {
    await page.getByRole("button", { name: "Keys" }).click({ force: true });
    await expect(page.getByText("Access Keys")).toBeVisible();
  });

  test("navigates to Tokens screen", async ({ page }) => {
    await page.getByRole("button", { name: "Tokens" }).click({ force: true });
    await expect(page.getByText("Create Token")).toBeVisible();
  });

  test("navigates to Nodes screen", async ({ page }) => {
    await page.getByRole("button", { name: "Nodes" }).click({ force: true });
    await expect(page.getByText("Snapshot")).toBeVisible();
  });

  test("navigates to Workers screen", async ({ page }) => {
    await page.getByRole("button", { name: "Workers" }).click({ force: true });
    await expect(page.getByText("Busy only")).toBeVisible();
  });

  test("navigates to Blocks screen", async ({ page }) => {
    await page.getByRole("button", { name: "Blocks" }).click({ force: true });
    await expect(
      page.getByText("Block Errors", { exact: true })
    ).toBeVisible();
  });
});
