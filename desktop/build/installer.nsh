!ifdef BUILD_UNINSTALLER
  !define CLASSNOTES_LANG_PREFIX "un."
!else
  !define CLASSNOTES_LANG_PREFIX ""
!endif

!macro customHeader
  LangString ${CLASSNOTES_LANG_PREFIX}WelcomeTitle 2052 "欢迎安装课记"
  LangString ${CLASSNOTES_LANG_PREFIX}WelcomeTitle 1033 "Welcome to ClassNotes"
  LangString ${CLASSNOTES_LANG_PREFIX}WelcomeText 2052 "将课堂录音整理为可编辑的知识笔记。$\r$\n$\r$\n安装包包含本地运行环境。首次转录会下载语音模型。$\r$\n$\r$\n升级只替换程序，录音、笔记、模型缓存和配置保存在独立的用户目录。卸载程序默认保留这些资料。"
  LangString ${CLASSNOTES_LANG_PREFIX}WelcomeText 1033 "Turn lecture recordings into editable knowledge notes.$\r$\n$\r$\nLocal runtimes are included. The speech model downloads on first use.$\r$\n$\r$\nUpdates replace program files. Recordings, notes, cached models and settings stay in a separate user folder and are kept on uninstall."
  LangString ${CLASSNOTES_LANG_PREFIX}CloseFirst 2052 "请先保存编辑并退出课记，再重新运行安装或卸载程序。"
  LangString ${CLASSNOTES_LANG_PREFIX}CloseFirst 1033 "Save your work and quit ClassNotes before running the installer or uninstaller again."
  LangString ${CLASSNOTES_LANG_PREFIX}CheckFailed 2052 "无法检查课记是否已退出，请稍后重试。错误代码：$R0"
  LangString ${CLASSNOTES_LANG_PREFIX}CheckFailed 1033 "Could not check whether ClassNotes has exited. Try again later. Error code: $R0"
  LangString ${CLASSNOTES_LANG_PREFIX}ReplaceFailed 2052 "旧程序未能安全替换（错误 $R0）。请退出课记后重试。录音与笔记资料仍然保留。"
  LangString ${CLASSNOTES_LANG_PREFIX}ReplaceFailed 1033 "The previous installation could not be safely replaced (error $R0). Quit ClassNotes and retry. Recordings and notes are preserved."
  LangString ${CLASSNOTES_LANG_PREFIX}StageFailed 2052 "无法建立程序升级暂存目录，原安装仍然保留。"
  LangString ${CLASSNOTES_LANG_PREFIX}StageFailed 1033 "Could not create the update staging folder. The original installation is preserved."
  LangString ${CLASSNOTES_LANG_PREFIX}RenameFailed 2052 "无法替换程序目录，请先关闭课记再重试。原安装仍然保留。"
  LangString ${CLASSNOTES_LANG_PREFIX}RenameFailed 1033 "Could not replace the program folder. Quit ClassNotes and retry. The original installation is preserved."
!macroend

!macro customWelcomePage
  !define MUI_WELCOMEPAGE_TITLE "$(${CLASSNOTES_LANG_PREFIX}WelcomeTitle)"
  !define MUI_WELCOMEPAGE_TEXT "$(${CLASSNOTES_LANG_PREFIX}WelcomeText)"
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
      MessageBox MB_OK|MB_ICONEXCLAMATION "$(${CLASSNOTES_LANG_PREFIX}ReplaceFailed)" /SD IDOK
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
      MessageBox MB_OK|MB_ICONEXCLAMATION "$(${CLASSNOTES_LANG_PREFIX}CloseFirst)" /SD IDOK
      SetErrorLevel 2
      Quit
    ${elseif} $R0 != 603
      MessageBox MB_OK|MB_ICONEXCLAMATION "$(${CLASSNOTES_LANG_PREFIX}CheckFailed)" /SD IDOK
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
      Abort "$(${CLASSNOTES_LANG_PREFIX}StageFailed)"
    ${endif}
    Delete "$R9"
    CreateDirectory "$R9"
    SetOutPath "$TEMP"
    ClearErrors
    Rename "$INSTDIR" "$R9\previous"
    ${if} ${Errors}
      RMDir "$R9"
      Abort "$(${CLASSNOTES_LANG_PREFIX}RenameFailed)"
    ${endif}
    ; Only the unique directory created above contains the previous program.
    RMDir /r "\\?\$R9\previous"
    RMDir "\\?\$R9"
  ${else}
    SetOutPath "$TEMP"
    RMDir /r "\\?\$INSTDIR"
  ${endif}
!macroend
