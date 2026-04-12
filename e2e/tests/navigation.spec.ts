import { test, expect } from "@playwright/test";

test.describe("Sidebar Navigation", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await expect(page.locator("text=Garage Admin")).toBeVisible({
      timeout: 30_000,
    });
  });

  test("navigates to Cluster screen", async ({ page }) => {
    await page.locator("text=Cluster").click();
    await expect(page.locator("text=Cluster Nodes")).toBeVisible();
  });

  test("navigates to Layout screen", async ({ page }) => {
    await page.locator("text=Layout").click();
    await expect(page.locator("text=Cluster Layout")).toBeVisible();
  });

  test("navigates to Buckets screen", async ({ page }) => {
    await page.locator("text=Buckets").click();
    await expect(page.locator("text=Create Bucket")).toBeVisible();
  });

  test("navigates to Keys screen", async ({ page }) => {
    await page.locator("text=Keys").click();
    await expect(page.locator("text=Access Keys")).toBeVisible();
  });

  test("navigates to Tokens screen", async ({ page }) => {
    await page.locator("text=Tokens").click();
    await expect(page.locator("text=Create Token")).toBeVisible();
  });

  test("navigates to Nodes screen", async ({ page }) => {
    await page.locator("text=Nodes").click();
    await expect(page.locator("text=Snapshot")).toBeVisible();
  });

  test("navigates to Workers screen", async ({ page }) => {
    await page.locator("text=Workers").click();
    await expect(page.locator("text=Workers")).toBeVisible();
  });

  test("navigates to Blocks screen", async ({ page }) => {
    await page.locator("text=Blocks").click();
    await expect(page.locator("text=Block Errors")).toBeVisible();
  });
});
