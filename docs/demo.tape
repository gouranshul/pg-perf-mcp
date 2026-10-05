# Recording script for docs/demo.gif (https://github.com/charmbracelet/vhs).
# With the compose stack running and ./demo/workload.sh done, from the repository root:
#
#   docker build -t pg-perf-vhs docs/vhs
#   docker run --rm -v "$PWD:/vhs" --network pg-perf-mcp_default -e BASE_URL=http://app:8080 pg-perf-vhs docs/demo.tape

Output docs/demo.gif

Set Shell bash
Set FontSize 15
Set Width 1180
Set Height 470
Set Padding 24
Set Theme "Catppuccin Mocha"
Set TypingSpeed 45ms
Set Framerate 20

Hide
Type "export PS1='$ ' && clear"
Enter
Show

Type "./demo/tour.sh"
Sleep 600ms
Enter
Wait+Screen@60s /suggestions are text/
Sleep 4s
