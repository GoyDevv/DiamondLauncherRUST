// Copyright 2026 The DiamondLauncher, Horizon, and BoardBridge Authors
// Licensed under the Apache License, Version 2.0.

//! GLFW 3 compatibility backend for Minecraft ≤ 26.2.
//!
//! Tracks per-window key state (`glfwGetKey`), mouse button state
//! (`glfwGetMouseButton`), virtual cursor coordinates (`glfwGetCursorPos`), and
//! cursor mode (`GLFW_CURSOR_NORMAL` / `GLFW_CURSOR_DISABLED`) on top of
//! BoardBridge's unified event queue.

use std::sync::Mutex;

use crate::error::Result;
use crate::graphics::BackendStatus;
use crate::input::event::{InputEvent, MouseButton};
use crate::input::QueueStats;
use crate::platform::{PlatformBackend, PlatformKind, WindowInfo};
use crate::runtime::control::Shared;

/// `GLFW_CURSOR_NORMAL`.
pub const GLFW_CURSOR_NORMAL: i32 = 0x0003_4001;
/// `GLFW_CURSOR_HIDDEN`.
pub const GLFW_CURSOR_HIDDEN: i32 = 0x0003_4002;
/// `GLFW_CURSOR_DISABLED` (captured relative pointer for camera steering).
pub const GLFW_CURSOR_DISABLED: i32 = 0x0003_4003;

/// Per-window GLFW input state updated on the game thread during `poll_event`.
#[derive(Debug)]
pub struct GlfwWindowState {
    keys: [bool; 512],
    mouse_buttons: [bool; 8],
    cursor_x: f64,
    cursor_y: f64,
    cursor_mode: i32,
    should_close: bool,
}

impl Default for GlfwWindowState {
    fn default() -> Self {
        Self {
            keys: [false; 512],
            mouse_buttons: [false; 8],
            cursor_x: 0.0,
            cursor_y: 0.0,
            cursor_mode: GLFW_CURSOR_NORMAL,
            should_close: false,
        }
    }
}

impl GlfwWindowState {
    /// Applies one [`InputEvent`] to the GLFW window state.
    pub fn apply(&mut self, event: &InputEvent) {
        match event {
            InputEvent::Key {
                pressed, scancode, ..
            } => {
                let idx = (*scancode as usize).min(self.keys.len() - 1);
                self.keys[idx] = *pressed;
            }
            InputEvent::MouseMotion {
                x, y, relative, ..
            } => {
                if *relative || self.cursor_mode == GLFW_CURSOR_DISABLED {
                    self.cursor_x += *x as f64;
                    self.cursor_y += *y as f64;
                } else {
                    self.cursor_x = *x as f64;
                    self.cursor_y = *y as f64;
                }
            }
            InputEvent::MouseButton {
                button, pressed, ..
            } => {
                let idx = match button {
                    MouseButton::Left => 0,
                    MouseButton::Right => 1,
                    MouseButton::Middle => 2,
                    MouseButton::Back => 3,
                    MouseButton::Forward => 4,
                };
                self.mouse_buttons[idx] = *pressed;
            }
            _ => {}
        }
    }

    /// Queries whether an SDL/GLFW scancode is currently pressed.
    pub fn is_key_pressed(&self, scancode: u16) -> bool {
        let idx = (scancode as usize).min(self.keys.len() - 1);
        self.keys[idx]
    }

    /// Current virtual cursor position.
    pub fn cursor_pos(&self) -> (f64, f64) {
        (self.cursor_x, self.cursor_y)
    }

    /// Sets cursor mode (`GLFW_CURSOR_NORMAL` or `GLFW_CURSOR_DISABLED`).
    pub fn set_cursor_mode(&mut self, mode: i32) {
        self.cursor_mode = mode;
    }

    /// Current cursor mode.
    pub fn cursor_mode(&self) -> i32 {
        self.cursor_mode
    }

    /// Whether the window was flagged to close.
    pub fn should_close(&self) -> bool {
        self.should_close
    }
}

/// GLFW compatibility platform backend.
pub struct GlfwCompatPlatformBackend {
    state: Mutex<GlfwWindowState>,
}

impl Default for GlfwCompatPlatformBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl GlfwCompatPlatformBackend {
    /// Creates a new GLFW compatibility backend.
    pub fn new() -> Self {
        Self {
            state: Mutex::new(GlfwWindowState::default()),
        }
    }
}

impl PlatformBackend for GlfwCompatPlatformBackend {
    fn kind(&self) -> PlatformKind {
        PlatformKind::GlfwCompat
    }

    fn name(&self) -> &'static str {
        "glfw-compat"
    }

    fn status(&self) -> BackendStatus {
        BackendStatus::Implemented
    }

    fn remaining_work(&self) -> Option<&'static str> {
        None
    }

    fn api_surface(&self) -> &'static str {
        "GLFW 3 compatibility state + callback dispatcher for Minecraft <= 26.2"
    }

    fn window_info(&self, shared: &Shared) -> Result<WindowInfo> {
        let backend = shared.backend()?;
        let stats = backend.stats();
        let size = backend.window_size();
        Ok(WindowInfo {
            width: size.width,
            height: size.height,
            has_surface: stats.has_window,
            binding_generation: backend.binding_generation(),
        })
    }

    fn queue_stats(&self, shared: &Shared) -> Result<QueueStats> {
        Ok(shared.queue_stats())
    }

    fn poll_event(&self, shared: &Shared) -> Result<Option<InputEvent>> {
        let next = shared.input_queue().pop();
        if let Some(ref ev) = next {
            let mut st = self
                .state
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            st.apply(ev);
        }
        Ok(next)
    }

    fn push_event(&self, shared: &Shared, event: InputEvent) -> Result<bool> {
        Ok(shared.input_queue().push(event))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::input::event::DeviceKind;
    use crate::input::sdl_tables as sdl;

    #[test]
    fn glfw_compat_tracks_keys_and_virtual_cursor() {
        let mut st = GlfwWindowState::default();
        st.set_cursor_mode(GLFW_CURSOR_DISABLED);
        st.apply(&InputEvent::Key {
            pressed: true,
            repeat: false,
            scancode: sdl::SDL_SCANCODE_W,
            keycode: 'w' as u32,
            modifiers: 0,
            android_keycode: 51,
            device: DeviceKind::Keyboard,
            timestamp_ms: 10,
        });
        assert!(st.is_key_pressed(sdl::SDL_SCANCODE_W));
        st.apply(&InputEvent::MouseMotion {
            x: 12.5,
            y: -4.0,
            relative: true,
            buttons: 0,
            timestamp_ms: 12,
        });
        assert_eq!(st.cursor_pos(), (12.5, -4.0));
    }
}