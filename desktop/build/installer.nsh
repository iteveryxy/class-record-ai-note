!macro customWelcomePage
  !define MUI_WELCOMEPAGE_TITLE "欢迎安装课记"
  !define MUI_WELCOMEPAGE_TEXT "将课堂录音整理为可编辑的知识笔记。$\r$\n$\r$\n安装包包含本地运行环境。首次转录会下载语音模型。$\r$\n$\r$\n升级只替换程序，录音、笔记、模型缓存和配置保存在独立的用户目录。卸载程序默认保留这些资料。"
  !insertmacro MUI_PAGE_WELCOME
!macroend

!macro customInit
  ; Release the installer's directory handle before invoking the old
  ; uninstaller. Otherwise Windows can block renaming that directory.
  SetOutPath "$TEMP"
!macroend

!macro customUnInstallCheck
  ${if} ${Errors}
  ${orIf} $R0 != 0
    ${ifNot} ${Silent}
      MessageBox MB_OK|MB_ICONEXCLAMATION "旧程序未能安全替换（错误 $R0）。请退出课记后重试。录音与笔记资料仍然保留。" /SD IDOK
    ${endif}
    SetErrorLevel 2
    Quit
  ${endif}
!macroend

!macro customUnInstallCheckCurrentUser
  !insertmacro customUnInstallCheck
!macroend

; Match the main executable explicitly. The default path-prefix process scan
; can include the copied update uninstaller before its install path is resolved.
; Do not force-kill services or discard unsaved editor work during installation.
!macro customCheckAppRunning
  StrCpy $R8 0
  classnotes_wait_for_exit:
    ${nsProcess::FindProcess} "${APP_EXECUTABLE_FILENAME}" $R0
    ${if} $R0 == 0
      IntOp $R8 $R8 + 1
      ${if} $R8 < 80
        Sleep 100
        Goto classnotes_wait_for_exit
      ${endif}
      MessageBox MB_OK|MB_ICONEXCLAMATION "请先保存编辑并退出课记，再重新运行安装或卸载程序。" /SD IDOK
      SetErrorLevel 2
      Quit
    ${elseif} $R0 != 603
      MessageBox MB_OK|MB_ICONEXCLAMATION "无法检查课记是否已退出，请稍后重试。错误代码：$R0" /SD IDOK
      SetErrorLevel 2
      Quit
    ${endif}
    ${nsProcess::Unload}
!macroend

; Stage the entire old program on its own drive. This avoids both cross-drive
; moves and per-file staging of deeply nested Python dependencies. Failed
; renames leave the original installation intact; use extended paths to remove
; files beyond the legacy Windows MAX_PATH limit.
!macro customRemoveFiles
  ${if} ${isUpdated}
    ${GetParent} "$INSTDIR" $R8
    ClearErrors
    GetTempFileName $R9 "$R8"
    ${if} ${Errors}
      Abort "无法建立程序升级暂存目录，原安装仍然保留。"
    ${endif}
    Delete "$R9"
    CreateDirectory "$R9"
    SetOutPath "$TEMP"
    ClearErrors
    Rename "$INSTDIR" "$R9\previous"
    ${if} ${Errors}
      RMDir "$R9"
      Abort "无法替换程序目录，请先关闭课记再重试。原安装仍然保留。"
    ${endif}
    ; Only the unique directory created above contains the previous program.
    RMDir /r "\\?\$R9\previous"
    RMDir "\\?\$R9"
  ${else}
    SetOutPath "$TEMP"
    RMDir /r "\\?\$INSTDIR"
  ${endif}
!macroend
