import { initializeKakaoSDK } from "@react-native-kakao/core";
import { render, waitFor } from "@testing-library/react-native";
import { KakaoSdkBootstrap } from "../../lib/KakaoSdkBootstrap";

jest.mock("../../api/config", () => ({
  API_BASE_URL: "https://api.example.test",
  KAKAO_NATIVE_APP_KEY: "native-key",
}));

const mockInitialize = initializeKakaoSDK as jest.MockedFunction<
  typeof initializeKakaoSDK
>;

describe("KakaoSdkBootstrap", () => {
  it("initializes the SDK once when mounted", async () => {
    mockInitialize.mockResolvedValue(undefined);

    render(<KakaoSdkBootstrap />);

    await waitFor(() => expect(mockInitialize).toHaveBeenCalledTimes(1));
    expect(mockInitialize).toHaveBeenCalledWith("native-key");
  });
});
