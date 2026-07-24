import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithProviders } from "@/test/common_setup";
import ResponsiveTable from "./index";

type Row = {
  id: string;
  name: string;
  status: string;
};

describe("ResponsiveTable", () => {
  it("uses the same columns to render labeled mobile cards", async () => {
    const onOpen = vi.fn();
    const user = userEvent.setup();

    renderWithProviders(
      <ResponsiveTable<Row>
        rowKey="id"
        pagination={false}
        dataSource={[{ id: "1", name: "Assembly plan", status: "Ready" }]}
        columns={[
          { title: "Name", dataIndex: "name" },
          { title: "Status", dataIndex: "status" },
          {
            title: "Actions",
            key: "actions",
            render: (_, row) => (
              <button type="button" onClick={() => onOpen(row.id)}>
                Open
              </button>
            ),
          },
        ]}
      />,
    );

    const card = document.querySelector("article");
    expect(card).not.toBeNull();
    expect(within(card!).getByText("Assembly plan")).toBeInTheDocument();
    expect(within(card!).getByText("Status")).toBeInTheDocument();
    expect(within(card!).getByText("Ready")).toBeInTheDocument();

    await user.click(
      within(card!).getByRole("button", { name: "Open", hidden: true }),
    );
    expect(onOpen).toHaveBeenCalledWith("1");
  });

  it("paginates the mobile card list with the table page size", () => {
    renderWithProviders(
      <ResponsiveTable<Row>
        rowKey="id"
        pagination={{ pageSize: 1 }}
        dataSource={[
          { id: "1", name: "First", status: "Ready" },
          { id: "2", name: "Second", status: "Queued" },
        ]}
        columns={[{ title: "Name", dataIndex: "name" }]}
      />,
    );

    const card = document.querySelector("article");
    expect(card).not.toBeNull();
    expect(within(card!).getByText("First")).toBeInTheDocument();
    expect(screen.getByRole("listitem", { name: "2" })).toBeInTheDocument();
  });
});
