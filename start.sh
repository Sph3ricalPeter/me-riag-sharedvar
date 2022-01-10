# install folder
FOLDER="target"

# default ID is 1
ID=1
P="2010"
GW=""
GWP="2010"

# read ID from flags
while getopts i:g:gp: flag; do
  case "${flag}" in
  i) ID=${OPTARG} ;;
  g) GW=${OPTARG} ;;
  p) GWP=${OPTARG} ;;
  *) ;;
  esac
done

F_PATH="${FOLDER}/me-riag-sharedvar-1.0-SNAPSHOT_${ID}.jar"

# copy main file
cp "${FOLDER}/me-riag-sharedvar-1.0-SNAPSHOT.jar" "${F_PATH}"

# run
if test -z "${GW}"; then
  java -jar "${F_PATH}" "${P}"
else
  java -jar "${F_PATH}" "${P}" "${GW}" "${GWP}"
fi
