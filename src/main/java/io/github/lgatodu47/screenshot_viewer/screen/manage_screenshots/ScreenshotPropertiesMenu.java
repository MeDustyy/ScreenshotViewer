package io.github.lgatodu47.screenshot_viewer.screen.manage_screenshots;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lgatodu47.screenshot_viewer.ScreenshotViewer;
import io.github.lgatodu47.screenshot_viewer.ScreenshotViewerMacOsUtils;
import io.github.lgatodu47.screenshot_viewer.config.ScreenshotViewerOptions;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.AbstractParentElement;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static io.github.lgatodu47.screenshot_viewer.screen.manage_screenshots.ManageScreenshotsScreen.LOGGER;
import static io.github.lgatodu47.screenshot_viewer.screen.manage_screenshots.ManageScreenshotsScreen.forEachDrawable;

class ScreenshotPropertiesMenu extends AbstractParentElement implements Drawable {
    @Nullable
    private static final Clipboard AWT_CLIPBOARD = tryGetAWTClipboard();
    private static final int BUTTON_SIZE = 19;

    private final Supplier<MinecraftClient> mcSupplier;
    private final IntSupplier parentWidth, parentHeight;
    private final List<ClickableWidget> buttons = new ArrayList<>();

    private int x, y, width, height;
    @NotNull
    private String fileName = "";
    private boolean shouldRender;
    @Nullable
    private Screen childScreen;

    ScreenshotPropertiesMenu(Supplier<MinecraftClient> mcSupplier, IntSupplier parentWidth, IntSupplier parentHeight) {
        this.mcSupplier = mcSupplier;
        this.parentWidth = parentWidth;
        this.parentHeight = parentHeight;
    }

    void tick() {
        if (childScreen != null) {
            childScreen.tick();
        }
    }

    void show(int x, int y, Runnable remover, File screenshotFile, Consumer<File> fileUpdater) {
        buttons.clear();

        this.fileName = screenshotFile.getName();
        final int spacing = 2;
        BooleanConsumer deleteAction = value -> {
            if (value) {
                if (screenshotFile.exists() && !screenshotFile.delete()) {
                    LOGGER.error("Failed to delete 'screenshot' file at location '{}'", screenshotFile.toPath().toAbsolutePath());
                    return;
                }
                remover.run();
            }
            hide();
        };
        // List of info about the buttons that are included in the widget.
        List<Triple<Integer, Text, ButtonWidget.PressAction>> buttonInfo = List.of(
                Triple.of(0, ScreenshotViewer.translatable("screen", "button.delete_screenshot"), btn -> {
                    if (ManageScreenshotsScreen.CONFIG.getOrFallback(ScreenshotViewerOptions.PROMPT_WHEN_DELETING_SCREENSHOT, true)) {
                        childScreen = new ConfirmScreen(deleteAction, screenshotFile.getName());
                        childScreen.init(this.mcSupplier.get(), parentWidth.getAsInt(), parentHeight.getAsInt());
                    } else {
                        deleteAction.accept(true);
                    }
                }),
                Triple.of(1, ScreenshotViewer.translatable("screen", "button.open_file"), btn -> Util.getOperatingSystem().open(screenshotFile)),
                Triple.of(4, ScreenshotViewer.translatable("screen", "button.copy_screenshot"), btn -> {
                    copyImageToClipboard(screenshotFile);
                    hide();
                }),
                Triple.of(3, ScreenshotViewer.translatable("screen", "button.rename_file"), btn -> {
                    childScreen = new RenameScreen(fileName.substring(0, fileName.lastIndexOf('.')), s -> {
                        try {
                            Path moved = Files.move(screenshotFile.toPath(), screenshotFile.toPath().resolveSibling(s));
                            fileUpdater.accept(moved.toFile());
                        } catch (IOException e) {
                            LOGGER.error("Failed to rename 'screenshot' file at '" + screenshotFile.toPath().toAbsolutePath() + "' from '" + screenshotFile.getName() + "' to '" + s + "'" , e);
                        }
                    }, this::hide);
                    childScreen.init(this.mcSupplier.get(), parentWidth.getAsInt(), parentHeight.getAsInt());
                }),
                Triple.of(2, ScreenshotViewer.translatable("screen", "button.close_properties_menu"), btn -> hide())
        );

        TextRenderer font = mcSupplier.get().textRenderer;
        final int largestTextWidth = buttonInfo.stream().map(Triple::getMiddle).mapToInt(font::getWidth).max().orElse(0);
        this.width = spacing * 2 + Math.max(font.getWidth(this.fileName), BUTTON_SIZE + largestTextWidth + spacing);
        this.height = spacing * 3 + font.fontHeight + BUTTON_SIZE * buttonInfo.size();

        // Offset the widget if it goes out of the screen
        if (x + width > parentWidth.getAsInt()) {
            this.x = x - width;
        } else {
            this.x = x;
        }
        if (y + height > parentHeight.getAsInt()) {
            this.y = y - height;
        } else {
            this.y = y;
        }

        for (int i = 0; i < buttonInfo.size(); i++) {
            Triple<Integer, Text, ButtonWidget.PressAction> pair = buttonInfo.get(i);
            buttons.add(new Button(this.x + spacing, this.y + spacing * 2 + font.fontHeight + BUTTON_SIZE * i, BUTTON_SIZE * pair.getLeft(), BUTTON_SIZE, pair.getMiddle(), pair.getRight()));
        }

        shouldRender = true;
    }

    void hide() {
        buttons.clear();
        shouldRender = false;
        childScreen = null;
        x = y = width = height = 0;
        fileName = "";
    }

    void copyImageToClipboard(File screenshotFile) {
        if(MinecraftClient.IS_SYSTEM_MAC) {
            ScreenshotViewerMacOsUtils.doCopyMacOS(screenshotFile.getAbsolutePath());
            return;
        }
        if(AWT_CLIPBOARD != null && screenshotFile.exists()) {
            CompletableFuture.runAsync(() -> {
                try {
                    BufferedImage img = ImageIO.read(screenshotFile);
                    BufferedImage rgbImg = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                    rgbImg.createGraphics().drawImage(img, 0, 0, img.getWidth(), img.getHeight(), null);
                    ImageTransferable imageTransferable = new ImageTransferable(rgbImg);
                    AWT_CLIPBOARD.setContents(imageTransferable, null);
                } catch (Throwable t) {
                    LOGGER.error("Failed to copy screenshot image to clipboard!", t);
                }
            }, Util.getMainWorkerExecutor());
        }
    }

    boolean renders() {
        return shouldRender;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (shouldRender) {
            MatrixStack matrices = context.getMatrices();
            matrices.push();
            matrices.translate(0, 0, 1);
            if (childScreen == null) {
                final int spacing = 2;
                context.fill(x, y, x + width, y + height, 0xFF424242);
                context.drawTextWithShadow(mcSupplier.get().textRenderer, fileName, x + spacing, y + spacing, 0xFFFFFFFF);
                for (ClickableWidget widget : buttons) {
                    widget.render(context, mouseX, mouseY, delta);
                    context.drawTextWithShadow(mcSupplier.get().textRenderer, widget.getMessage(), widget.getX() + widget.getWidth() + spacing, (int) (widget.getY() + (widget.getHeight() - 9) / 2.f + spacing), 0xFFFFFFFF);
                }
            } else {
                childScreen.render(context, mouseX, mouseY, delta);
            }
            matrices.pop();
        }
    }

    @Override
    public List<? extends Element> children() {
        return buttons;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (childScreen != null) {
            return childScreen.mouseClicked(mouseX, mouseY, button);
        }
        if (mouseX < x || mouseY < y || mouseX > x + width || mouseY > y + height) {
            hide();
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputUtil.GLFW_KEY_ESCAPE) {
            hide();
            return true;
        }
        if (childScreen instanceof RenameScreen) {
            return childScreen.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (childScreen instanceof RenameScreen) {
            return childScreen.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    @Nullable
    private static Clipboard tryGetAWTClipboard() {
        if(MinecraftClient.IS_SYSTEM_MAC) {
            return null;
        }
        try {
            return Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (Throwable t) {
            LOGGER.error("Unable to retrieve Java AWT Clipboard instance!", t);
        }
        return null;
    }

    private static final class Button extends TexturedButtonWidget {
        private static final Identifier TEXTURE = new Identifier(ScreenshotViewer.MODID, "textures/gui/screenshot_viewer_icons.png");
        private final int imgU, imgV;

        public Button(int x, int y, int imgU, int imgV, Text title, PressAction pressAction) {
            super(x, y, BUTTON_SIZE, BUTTON_SIZE, ManageScreenshotsScreen.DEFAULT_BUTTON_TEXTURES, pressAction, title);
            this.imgU = imgU;
            this.imgV = imgV;
        }

        @Override
        public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            RenderSystem.enableDepthTest();
            context.drawTexture(TEXTURE, this.getX(), this.getY(), isHovered() ? BUTTON_SIZE : BUTTON_SIZE * 2, 0, this.width, this.height, 128, 128);
            context.drawTexture(TEXTURE, this.getX(), this.getY(), this.imgU, this.imgV, this.width, this.height, 128, 128);
        }
    }

    private static final class ConfirmScreen extends net.minecraft.client.gui.screen.ConfirmScreen {
        public ConfirmScreen(BooleanConsumer callback, String fileName) {
            super(callback, Text.translatable("screen." + ScreenshotViewer.MODID + ".delete_prompt", fileName), ScreenshotViewer.translatable("screen", "delete_prompt.message"));
        }

        @Override
        public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
        }
    }

    private static final class RenameScreen extends Screen {
        private final String previousName;
        private final Consumer<String> newNameConsumer;
        private final Runnable closeAction;
        private ButtonWidget doneBtn;

        private RenameScreen(String previousName, Consumer<String> newNameConsumer, Runnable closeAction) {
            super(ScreenshotViewer.translatable("screen", "rename_screenshot"));
            this.previousName = previousName;
            this.newNameConsumer = newNameConsumer;
            this.closeAction = closeAction;
        }

        @Override
        protected void init() {
            super.init();
            TextFieldWidget textField = new TextFieldWidget(this.textRenderer, (this.width - 150) / 2, (this.height - 20) / 2, 150, 20, ScreenshotViewer.translatable("screen", "field.screenshot_name"));
            textField.setMaxLength(128);
            textField.setTextPredicate(RenameScreen::checkInvalidCharacters);
            this.doneBtn = ButtonWidget.builder(ScreenTexts.DONE, btn -> {
                this.newNameConsumer.accept(textField.getText().trim().concat(".png"));
                this.closeAction.run();
            }).position(this.width / 2 - 4 - 150, this.height / 2 + 50).build();
            doneBtn.active = false;
            textField.setChangedListener(s -> doneBtn.active = !(s.isBlank() || s.trim().equals(previousName) || s.endsWith(".")));
            textField.setText(previousName);
            this.addDrawableChild(textField);
            this.addDrawableChild(doneBtn);
            this.addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, btn -> closeAction.run()).position(this.width / 2 + 4, this.height / 2 + 50).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 70, 0xFFFFFF);
            forEachDrawable(this, drawable -> drawable.render(context, mouseX, mouseY, delta));
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == InputUtil.GLFW_KEY_ENTER && doneBtn != null && doneBtn.active) {
                doneBtn.onPress();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void close() {
            this.closeAction.run();
        }

        private static boolean checkInvalidCharacters(String s) {
            return s.chars().noneMatch(c -> c == '\\' || c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|');
        }
    }

    private record ImageTransferable(Image image) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Nonnull
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if(!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image();
        }
    }
}
