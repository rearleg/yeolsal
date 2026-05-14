// WelcomeWindow integration — Story 1.6 AC1, AC4.
//
// Asserts the gating predicate `shouldShowWelcomeWindow` used by the room
// detail screen (FE/app/rooms/[id].tsx) to decide whether to mount the
// WelcomeWindow. The predicate is the single seat of the rendering rule,
// so this test doubles as the integration boundary between the screen
// and the leaf component.

import { render, screen } from "@testing-library/react-native";
import { SubModeProvider } from "../../../providers/SubModeProvider";
import { shouldShowWelcomeWindow, WelcomeWindow } from "../WelcomeWindow";

const NOW = new Date("2026-05-14T10:00:00Z");
const TEN_DAYS_AGO = new Date("2026-05-04T10:00:00Z"); // still inside 14d grace
const TWENTY_DAYS_AGO = new Date("2026-04-24T10:00:00Z"); // grace ended

describe("shouldShowWelcomeWindow predicate (AC1 gating)", () => {
  it("returns true: leader + sole member + grace still open", () => {
    expect(
      shouldShowWelcomeWindow({
        currentUserId: 7,
        ownerId: 7,
        memberCount: 1,
        roomCreatedAt: TEN_DAYS_AGO,
        now: NOW,
      }),
    ).toBe(true);
  });

  it("returns false: viewer is not the leader (member, not owner)", () => {
    expect(
      shouldShowWelcomeWindow({
        currentUserId: 8,
        ownerId: 7,
        memberCount: 1,
        roomCreatedAt: TEN_DAYS_AGO,
        now: NOW,
      }),
    ).toBe(false);
  });

  it("returns true: leader + 2 members still inside grace (growing — still encourage invites)", () => {
    expect(
      shouldShowWelcomeWindow({
        currentUserId: 7,
        ownerId: 7,
        memberCount: 2,
        roomCreatedAt: TEN_DAYS_AGO,
        now: NOW,
      }),
    ).toBe(true);
  });

  it("returns false: grace ended (full surface stack takes over)", () => {
    expect(
      shouldShowWelcomeWindow({
        currentUserId: 7,
        ownerId: 7,
        memberCount: 2,
        roomCreatedAt: TWENTY_DAYS_AGO,
        now: NOW,
      }),
    ).toBe(false);
  });

  it("returns false: room.createdAt missing (defensive — never render with unknown grace)", () => {
    expect(
      shouldShowWelcomeWindow({
        currentUserId: 7,
        ownerId: 7,
        memberCount: 1,
        roomCreatedAt: null,
        now: NOW,
      }),
    ).toBe(false);
  });
});

describe("Room screen composition: WelcomeWindow renders only when predicate is true", () => {
  it("renders the component when the predicate is true", () => {
    const grace = new Date("2026-05-25T10:00:00Z");
    render(
      <SubModeProvider subMode="postcard">
        {shouldShowWelcomeWindow({
          currentUserId: 7,
          ownerId: 7,
          memberCount: 1,
          roomCreatedAt: TEN_DAYS_AGO,
          now: NOW,
        }) ? (
          <WelcomeWindow
            roomName="첫 그룹"
            memberCount={1}
            graceEndsAt={grace}
            now={NOW}
            onTapStartToday={() => undefined}
          />
        ) : null}
      </SubModeProvider>,
    );
    expect(screen.getByTestId("welcome-window")).toBeTruthy();
  });

  it("does NOT render the component when the predicate is false (full state)", () => {
    const grace = new Date("2026-05-08T10:00:00Z"); // ended before NOW
    render(
      <SubModeProvider subMode="postcard">
        {shouldShowWelcomeWindow({
          currentUserId: 7,
          ownerId: 7,
          memberCount: 4,
          roomCreatedAt: TWENTY_DAYS_AGO,
          now: NOW,
        }) ? (
          <WelcomeWindow
            roomName="첫 그룹"
            memberCount={4}
            graceEndsAt={grace}
            now={NOW}
            onTapStartToday={() => undefined}
          />
        ) : null}
      </SubModeProvider>,
    );
    expect(screen.queryByTestId("welcome-window")).toBeNull();
  });
});
